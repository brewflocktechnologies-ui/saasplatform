package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.Channels;
import com.byteflock.chatplatform.conversation.ConversationChange;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.outbox.OutboxAppender;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes one assignment projection in a short transaction: lock the row, compare {@code (state, agent)}, update, then —
 * only when that pair changed — upsert the AGENT participant, append one state event and exactly one
 * {@code conversation.updated} outbox row with the contact-safe assignment (R-P23, R-P3, R-P29). A projection that leaves
 * the pair unchanged is silent bookkeeping: no version bump, no event.
 */
@Component
class AssignmentProjection {

	private static final Caller WORKER = new Caller(null, PrincipalType.AGENT, "assignment-worker", null);

	private final TransactionTemplate tx;

	private final ConversationRepository conversations;

	private final ParticipantRepository participants;

	private final StateEventRepository stateEvents;

	private final OutboxAppender outbox;

	private final Clock clock;

	AssignmentProjection(TransactionTemplate tx, ConversationRepository conversations,
			ParticipantRepository participants, StateEventRepository stateEvents, OutboxAppender outbox, Clock clock) {
		this.tx = tx;
		this.conversations = conversations;
		this.participants = participants;
		this.stateEvents = stateEvents;
		this.outbox = outbox;
		this.clock = clock;
	}

	void assigned(ClaimedAssignment row, String agentId, UUID decisionId, String reason, String explanation) {
		write(row, AssignmentState.ASSIGNED, agentId, decisionId, reason, explanation, this.clock.instant());
	}

	void queued(ClaimedAssignment row, String reason, Instant nextAttemptAt) {
		write(row, AssignmentState.QUEUED, null, null, reason, null, nextAttemptAt);
	}

	void failed(ClaimedAssignment row, String reason, Instant nextAttemptAt) {
		write(row, AssignmentState.FAILED, null, null, reason, null, nextAttemptAt);
	}

	void released(ClaimedAssignment row) {
		write(row, AssignmentState.NONE, null, null, null, null, this.clock.instant());
	}

	void requested(ClaimedAssignment row, Instant nextAttemptAt) {
		write(row, AssignmentState.REQUESTED, null, null, "REQUEUED", null, nextAttemptAt);
	}

	/** Bookkeeping without a projection change (poll, backoff): no version bump and no event. */
	void reschedule(ClaimedAssignment row, String reason, Instant nextAttemptAt) {
		this.tx.executeWithoutResult((status) -> this.conversations.reschedule(row.id(), row.tenantId(),
				OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC)));
	}

	/** Counts a consecutive setup rejection (R-P2); the caller fails the row when the budget is exhausted. */
	int setupFailure(ClaimedAssignment row, Instant nextAttemptAt) {
		Integer count = this.tx.execute((status) -> {
			Optional<ConversationRow> locked = this.conversations.lockForUpdate(row.tenantId(), row.id());
			if (locked.isEmpty() || locked.get().assignmentState() != row.assignmentState()) {
				return 0;
			}
			return this.conversations.incrementSetupFailures(row.id(), row.tenantId(),
					OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC));
		});
		return (count != null) ? count : 0;
	}

	private void write(ClaimedAssignment row, AssignmentState target, String agentId, UUID decisionId, String reason,
			String explanation, Instant nextAttemptAt) {
		this.tx.executeWithoutResult((status) -> {
			Optional<ConversationRow> locked = this.conversations.lockForUpdate(row.tenantId(), row.id());
			if (locked.isEmpty()) {
				return;
			}
			ConversationRow current = locked.get();
			boolean changed = current.assignmentState() != target || !Objects.equals(current.assignedAgentId(), agentId);
			int updated = this.conversations.updateProjection(row.id(), row.tenantId(), target, agentId, decisionId,
					reason, explanation, OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC), 0, changed,
					OffsetDateTime.now(this.clock));
			if (updated == 0 || !changed) {
				return;
			}
			if (target == AssignmentState.ASSIGNED && agentId != null) {
				this.participants.ensure(row.id(), row.tenantId(), PrincipalType.AGENT.name(), agentId);
			}
			this.stateEvents.insert(row.id(), row.tenantId(), stateEvent(target), "SYSTEM", "assignment-worker",
					current.status().name(), current.status().name(), null);
			ConversationView view = this.conversations
				.findView(row.tenantId(), row.id(),
						new Caller(row.tenantId(), PrincipalType.AGENT, "assignment-worker", null))
				.orElse(null);
			if (view == null) {
				return;
			}
			this.outbox.append(row.tenantId(), "conversation", row.id().toString(), "conversation.updated",
					List.of(Channels.conversation(row.id()), Channels.inbox(row.tenantId(), row.inboxId())),
					EventPayloads.conversationUpdated(view, change(target)));
		});
	}

	private static String stateEvent(AssignmentState target) {
		return switch (target) {
			case ASSIGNED -> "ASSIGNED";
			case QUEUED -> "ASSIGNMENT_QUEUED";
			case FAILED -> "ASSIGNMENT_FAILED";
			case NONE -> "RELEASED";
			default -> "ASSIGNMENT_UPDATED";
		};
	}

	private static ConversationChange change(AssignmentState target) {
		return switch (target) {
			case ASSIGNED -> ConversationChange.ASSIGNED;
			case QUEUED -> ConversationChange.ASSIGNMENT_QUEUED;
			case FAILED -> ConversationChange.ASSIGNMENT_FAILED;
			default -> ConversationChange.ASSIGNMENT_UPDATED;
		};
	}

}
