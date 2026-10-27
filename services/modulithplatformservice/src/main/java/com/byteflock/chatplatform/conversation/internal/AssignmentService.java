package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.assignment.AllocationClient;
import com.byteflock.chatplatform.assignment.AllocationConversationState;
import com.byteflock.chatplatform.assignment.AllocationResult;
import com.byteflock.chatplatform.assignment.AllocationUnavailableException;
import com.byteflock.chatplatform.conversation.AssignMode;
import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.ConversationConflictException;
import com.byteflock.chatplatform.conversation.ConversationStatus;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /api/v1/agent/conversations/{id}/assign}: claim, auto-assign or reassign through the allocation service.
 * <p>
 * Checks run under the row lock (archived → 409 {@code CONVERSATION_ARCHIVED} (R-P31 M1), resolved → 409
 * {@code INVALID_TRANSITION}, stale {@code expectedVersion} → 409 {@code STALE_VERSION}); the engine call happens outside
 * any transaction, and its outcome is projected in its own transaction. Rejections answer 409
 * {@code ALLOCATION_REJECTED} with the engine code in the detail (R-P14); a repeated claim by the agent who already holds
 * the conversation answers 200 with the unchanged view (R-P19); an unavailable engine answers 503
 * {@code ALLOCATION_UNAVAILABLE}.
 */
@Service
public class AssignmentService {

	private final TransactionTemplate tx;

	private final ConversationAccess access;

	private final ConversationRepository conversations;

	private final AssignmentProjection projection;

	private final AllocationClient client;

	private final TenantDirectory directory;

	private final AssignmentProperties properties;

	private final Clock clock;

	AssignmentService(TransactionTemplate tx, ConversationAccess access, ConversationRepository conversations,
			AssignmentProjection projection, AllocationClient client, TenantDirectory directory,
			AssignmentProperties properties, Clock clock) {
		this.tx = tx;
		this.access = access;
		this.conversations = conversations;
		this.projection = projection;
		this.client = client;
		this.directory = directory;
		this.properties = properties;
		this.clock = clock;
	}

	public ConversationView assign(Caller caller, UUID conversationId, AssignMode mode, Long expectedVersion,
			String idempotencyKey) {
		ClaimedAssignment row = this.tx.execute((status) -> {
			ConversationRow locked = this.access.lockForWrite(caller, conversationId);
			if (locked.archived() || locked.purged()) {
				throw new ConversationConflictException("CONVERSATION_ARCHIVED");
			}
			if (locked.status() == ConversationStatus.RESOLVED) {
				throw new ConversationConflictException("INVALID_TRANSITION");
			}
			if (expectedVersion != null && expectedVersion != locked.version()) {
				throw new ConversationConflictException("STALE_VERSION");
			}
			String queueId = this.directory.inbox(locked.tenantId(), locked.inboxId())
				.map((inbox) -> inbox.allocationQueueId())
				.orElse(null);
			return new ClaimedAssignment(locked.id(), locked.tenantId(), locked.inboxId(), locked.contactId(), queueId,
					locked.status(), locked.assignmentState(), locked.archived(), OffsetDateTime.now(this.clock),
					locked.assignmentAttempts(), locked.assignmentSetupFailures());
		});
		String conversationKey = conversationId.toString();
		if (row.assignmentState() == AssignmentState.REQUESTED || row.assignmentState() == AssignmentState.FAILED) {
			this.client.createConversation(row.tenantId(), conversationKey, row.queueId(), this.clock.instant());
		}
		AllocationResult result = switch (mode) {
			case CLAIM -> this.client.claim(row.tenantId(), idempotencyKey, conversationKey, caller.principalId());
			case AUTO -> this.client.dispatch(row.tenantId(), idempotencyKey, conversationKey);
			case REASSIGN -> this.client.reassign(row.tenantId(), idempotencyKey, conversationKey);
		};
		applyOutcome(caller, row, result);
		return this.access.view(caller, conversationId);
	}

	private void applyOutcome(Caller caller, ClaimedAssignment row, AllocationResult result) {
		if (result.assigned()) {
			this.projection.assigned(row, result.agentId(), result.decisionId(), result.outcome(), result.explanation());
			return;
		}
		if (result.queued()) {
			this.projection.queued(row, result.outcome(),
					this.clock.instant().plus(this.properties.queuedPoll()));
			return;
		}
		if ("CONFLICT_RETRYABLE".equals(result.outcome())) {
			throw new ConversationConflictException("ALLOCATION_REJECTED", "CONFLICT_RETRYABLE");
		}
		boolean notAllocatable = "REJECTED_INVALID_INPUT".equals(result.outcome())
				&& result.reasonCodes().contains("CONVERSATION_NOT_ALLOCATABLE");
		if (notAllocatable || "NOT_ASSIGNED".equals(result.outcome())) {
			AssignmentState projected = projectEngineState(row);
			boolean callerHolds = this.access.view(caller, row.id()).assignment() != null
					&& caller.principalId().equals(this.access.view(caller, row.id()).assignment().agentId());
			if (callerHolds) {
				// A repeated claim by the holder is a 200 with the unchanged view (R-P19).
				return;
			}
			throw new ConversationConflictException("ALLOCATION_REJECTED", result.outcome());
		}
		if ("CLAIM_REJECTED".equals(result.outcome())) {
			throw new ConversationConflictException("ALLOCATION_REJECTED", "CLAIM_REJECTED");
		}
		if ("FAILED_INTERNAL".equals(result.outcome()) || "SKIPPED_POLICY_DISABLED".equals(result.outcome())
				|| "NO_WAITING_CONVERSATION".equals(result.outcome())
				|| "REJECTED_INVALID_INPUT".equals(result.outcome())) {
			this.projection.failed(row, result.outcome(), this.clock.instant().plus(this.properties.failedPoll()));
			throw new ConversationConflictException("ALLOCATION_REJECTED", result.outcome());
		}
		throw new ConversationConflictException("ALLOCATION_REJECTED", result.outcome());
	}

	/** Projects what the engine currently holds and returns the resulting state (R-P14, R-P17). */
	private AssignmentState projectEngineState(ClaimedAssignment row) {
		Optional<AllocationConversationState> engine = this.client.findConversation(row.tenantId(), row.id().toString());
		if (engine.isEmpty()) {
			this.projection.requested(row, this.clock.instant());
			return AssignmentState.REQUESTED;
		}
		AllocationConversationState state = engine.get();
		if (state.assigned()) {
			this.projection.assigned(row, state.assignedAgentId(), null, "ALLOCATION_SWEEP",
					"assigned by the allocation service");
			return AssignmentState.ASSIGNED;
		}
		return switch (state.status()) {
			case "QUEUED" -> {
				this.projection.queued(row, "QUEUED", this.clock.instant().plus(this.properties.queuedPoll()));
				yield AssignmentState.QUEUED;
			}
			case "CANCELLED" -> {
				this.projection.failed(row, "ALLOCATION_CANCELLED",
						this.clock.instant().plus(this.properties.failedPoll()));
				yield AssignmentState.FAILED;
			}
			case "RESOLVED" -> {
				this.projection.requested(row, this.clock.instant());
				yield AssignmentState.REQUESTED;
			}
			default -> row.assignmentState();
		};
	}

	/** Unused helper kept for symmetry with the orchestrator's projection path. */
	static OffsetDateTime at(Clock clock) {
		return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

}
