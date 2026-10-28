package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;

import com.byteflock.chatplatform.assignment.AllocationClient;
import com.byteflock.chatplatform.assignment.AllocationConversationState;
import com.byteflock.chatplatform.assignment.AllocationRejectedException;
import com.byteflock.chatplatform.assignment.AllocationResult;
import com.byteflock.chatplatform.assignment.AllocationUnavailableException;
import com.byteflock.chatplatform.outbox.Backoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * The assignment state machine of {@code docs/architecture/allocation-integration.md} §3. Engine calls happen outside any
 * transaction; every outcome is projected in its own short transaction with an {@code assignment_state} precondition.
 * <p>
 * Only {@code REQUESTED} dispatches (R-P1); {@code QUEUED} and {@code FAILED} are only read (R-P17); archived rows are
 * never dispatched (R-P13); transport failures back off and never park (R-P30 classification); setup rejections count
 * against {@code assignment_setup_failures} (R-P2); {@code RELEASE_PENDING} never becomes {@code FAILED} (R-P14).
 */
@Component
class AssignmentOrchestrator {

	static final Duration ALLOCATION_TIMEOUT = Duration.ofSeconds(3);

	private static final Logger log = LoggerFactory.getLogger(AssignmentOrchestrator.class);

	private final AllocationClient client;

	private final ConversationRepository conversations;

	private final AssignmentProjection projection;

	private final AssignmentProperties properties;

	private final Clock clock;

	private final RandomGenerator random = RandomGenerator.getDefault();

	AssignmentOrchestrator(AllocationClient client, ConversationRepository conversations,
			AssignmentProjection projection, AssignmentProperties properties, Clock clock) {
		this.client = client;
		this.conversations = conversations;
		this.projection = projection;
		this.properties = properties;
		this.clock = clock;
	}

	void process(ClaimedAssignment row) {
		Instant now = this.clock.instant();
		try {
			switch (row.assignmentState()) {
				case REQUESTED -> requested(row, now);
				case QUEUED -> poll(row, now);
				case RELEASE_PENDING -> release(row, now);
				case REOPEN_PENDING -> reopen(row, now);
				case FAILED -> failedReRead(row, now);
				default -> {
				}
			}
		}
		catch (AllocationUnavailableException ex) {
			transientFailure(row, now);
		}
		catch (AllocationRejectedException ex) {
			rejected(row, ex, now);
		}
	}

	private void requested(ClaimedAssignment row, Instant now) {
		Optional<AllocationConversationState> engine = this.client.findConversation(row.tenantId(), id(row));
		if (engine.isPresent() && applyEngineState(row, engine.get(), now)) {
			return;
		}
		if (guardChanged(row)) {
			return;
		}
		if (engine.isEmpty()) {
			this.client.createConversation(row.tenantId(), id(row), row.queueId(), now);
		}
		if (guardChanged(row)) {
			return;
		}
		dispatch(row, now);
	}

	private void dispatch(ClaimedAssignment row, Instant now) {
		AllocationResult result = this.client.dispatch(row.tenantId(), "assign:" + id(row) + ":" + row.attempts(),
				id(row));
		applyDispatchOutcome(row, result, now);
	}

	private void applyDispatchOutcome(ClaimedAssignment row, AllocationResult result, Instant now) {
		if (result.assigned()) {
			this.projection.assigned(row, result.agentId(), result.decisionId(), result.outcome(),
					result.explanation());
			return;
		}
		if (result.queued()) {
			this.projection.queued(row, result.outcome(), now.plus(this.properties.queuedPoll()));
			return;
		}
		if ("CONFLICT_RETRYABLE".equals(result.outcome())) {
			this.projection.reschedule(row, result.outcome(), now.plus(this.properties.pollDelay()));
			return;
		}
		if ("REJECTED_INVALID_INPUT".equals(result.outcome()) && result.reasonCodes().contains("CONVERSATION_NOT_ALLOCATABLE")) {
			// The sweep (or another claim) assigned it between our read and the dispatch: project what the engine holds.
			Optional<AllocationConversationState> engine = this.client.findConversation(row.tenantId(), id(row));
			if (engine.isEmpty()) {
				this.projection.requested(row, now);
			}
			else if (!applyEngineState(row, engine.get(), now)) {
				this.projection.failed(row, result.outcome(), now.plus(this.properties.failedPoll()));
			}
			return;
		}
		if ("CLAIM_REJECTED".equals(result.outcome())) {
			this.projection.failed(row, result.outcome(), now.plus(this.properties.failedPoll()));
			return;
		}
		this.projection.failed(row, result.outcome(), now.plus(this.properties.failedPoll()));
	}

	/** Projects an existing engine record; returns false when the status is one the caller must handle itself. */
	private boolean applyEngineState(ClaimedAssignment row, AllocationConversationState engine, Instant now) {
		if (engine.assigned()) {
			this.projection.assigned(row, engine.assignedAgentId(), null, "ALLOCATION_SWEEP",
					"assigned by the allocation sweep");
			return true;
		}
		if ("QUEUED".equals(engine.status())) {
			if (row.assignmentState() == com.byteflock.chatplatform.conversation.AssignmentState.QUEUED) {
				this.projection.reschedule(row, "QUEUED", now.plus(this.properties.queuedPoll()));
			}
			else {
				this.projection.queued(row, "QUEUED", now.plus(this.properties.queuedPoll()));
			}
			return true;
		}
		if ("RESOLVED".equals(engine.status())) {
			this.client.requeueReopen(row.tenantId(), id(row));
			this.projection.requested(row, now);
			return true;
		}
		if ("CANCELLED".equals(engine.status())) {
			this.projection.failed(row, "ALLOCATION_CANCELLED", now.plus(this.properties.failedPoll()));
			return true;
		}
		return false;
	}

	private void poll(ClaimedAssignment row, Instant now) {
		Optional<AllocationConversationState> engine = this.client.findConversation(row.tenantId(), id(row));
		if (engine.isEmpty()) {
			this.projection.requested(row, now);
		}
		else if (!applyEngineState(row, engine.get(), now)) {
			this.projection.reschedule(row, engine.get().status(), now.plus(this.properties.queuedPoll()));
		}
	}

	private void release(ClaimedAssignment row, Instant now) {
		try {
			this.client.resolve(row.tenantId(), id(row));
			this.projection.released(row);
		}
		catch (AllocationRejectedException ex) {
			if (ex.status() == 404 || ex.status() == 409) {
				Optional<AllocationConversationState> engine = this.client.findConversation(row.tenantId(), id(row));
				boolean gone = engine.isEmpty() || "RESOLVED".equals(engine.get().status())
						|| "CANCELLED".equals(engine.get().status());
				if (gone) {
					this.projection.released(row);
					return;
				}
			}
			// RELEASE_PENDING never becomes FAILED: keep the state, back off and make the code visible (R-P14).
			log.warn("Allocation release rejected eventId={} code={} status={}", row.id(), ex.code(), ex.status());
			this.projection.reschedule(row, ex.code(), Backoff.next(now, row.attempts() - 1,
					this.properties.pollDelay(), this.properties.maxBackoff(), this.random));
		}
	}

	private void reopen(ClaimedAssignment row, Instant now) {
		Optional<AllocationConversationState> engine = this.client.findConversation(row.tenantId(), id(row));
		if (engine.isEmpty()) {
			this.projection.requested(row, now);
			return;
		}
		AllocationConversationState state = engine.get();
		if (state.assigned()) {
			this.projection.assigned(row, state.assignedAgentId(), null, "REOPEN_KEPT_AGENT",
					"the allocation record still holds the agent");
		}
		else if ("QUEUED".equals(state.status())) {
			this.projection.queued(row, "QUEUED", now.plus(this.properties.queuedPoll()));
		}
		else if ("CANCELLED".equals(state.status())) {
			this.projection.failed(row, "ALLOCATION_CANCELLED", now.plus(this.properties.failedPoll()));
		}
		else {
			this.projection.requested(row, now);
		}
	}

	private void failedReRead(ClaimedAssignment row, Instant now) {
		Optional<AllocationConversationState> engine = this.client.findConversation(row.tenantId(), id(row));
		if (engine.isPresent() && engine.get().assigned()) {
			this.projection.assigned(row, engine.get().assignedAgentId(), null, "ALLOCATION_SWEEP",
					"the allocation sweep assigned the conversation later");
			return;
		}
		this.projection.reschedule(row, "FAILED_POLL", now.plus(this.properties.failedPoll()));
	}

	private void transientFailure(ClaimedAssignment row, Instant now) {
		Instant next = row.assignmentState() == com.byteflock.chatplatform.conversation.AssignmentState.FAILED
				? now.plus(this.properties.failedPoll())
				: Backoff.next(now, row.attempts() - 1, this.properties.pollDelay(), this.properties.maxBackoff(),
						this.random);
		this.projection.reschedule(row, "ALLOCATION_UNAVAILABLE", next);
	}

	private void rejected(ClaimedAssignment row, AllocationRejectedException ex, Instant now) {
		String code = ex.code();
		boolean setup = "TENANT_NOT_FOUND".equals(code) || "QUEUE_NOT_FOUND".equals(code)
				|| "NO_ACTIVE_POLICY".equals(code);
		Instant backoff = Backoff.next(now, row.attempts() - 1, this.properties.pollDelay(),
				this.properties.maxBackoff(), this.random);
		if (setup) {
			int failures = this.projection.setupFailure(row, backoff);
			if (failures >= this.properties.maxSetupAttempts()) {
				this.projection.failed(row, code, now.plus(this.properties.failedPoll()));
			}
			return;
		}
		boolean transientCode = "CONCURRENT_MODIFICATION".equals(code) || "IDEMPOTENCY_RACE".equals(code)
				|| "AGENT_VANISHED".equals(code);
		if (transientCode || row.assignmentState() == com.byteflock.chatplatform.conversation.AssignmentState.RELEASE_PENDING) {
			if (row.assignmentState() == com.byteflock.chatplatform.conversation.AssignmentState.RELEASE_PENDING) {
				log.warn("Allocation release failed eventId={} code={}", row.id(), code);
			}
			this.projection.reschedule(row, code, backoff);
			return;
		}
		this.projection.failed(row, code, now.plus(this.properties.failedPoll()));
	}

	/** Re-reads {@code (archived_at, assignment_state)} right before a create/dispatch call (R-P28). */
	private boolean guardChanged(ClaimedAssignment row) {
		Optional<AssignmentGuard> guard = this.conversations.assignmentGuard(row.tenantId(), row.id());
		return guard.isEmpty() || guard.get().archived() || guard.get().assignmentState() != row.assignmentState();
	}

	static String id(ClaimedAssignment row) {
		return row.id().toString();
	}

}
