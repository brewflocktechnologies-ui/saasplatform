package com.byteflock.chatplatform.conversation.internal;

import com.byteflock.chatplatform.conversation.ConversationChange;
import com.byteflock.chatplatform.conversation.ConversationConflictException;
import com.byteflock.chatplatform.conversation.ConversationStatus;
import org.springframework.stereotype.Component;

/**
 * The pure conversation state machine of {@code docs/architecture/domain-model.md} section 5.2: 3 statuses × 2 archive
 * states × 7 commands, with no I/O, no clock and no repository.
 * <p>
 * The service checks {@link #isSatisfied} first (an already-satisfied command is a 200 no-op even when its
 * {@code expectedVersion} is stale), then {@code expectedVersion}, and only then calls {@link #apply}, which throws
 * {@link ConversationConflictException} with {@code INVALID_TRANSITION}, {@code CONVERSATION_RESOLVED} or
 * {@code CONVERSATION_ARCHIVED}. An archived conversation accepts no status command at all, even a satisfied
 * {@code REOPEN} or {@code MARK_PENDING} (R-P8 amended by R-P31 M14), while a satisfied {@code RESOLVE} or
 * {@code ARCHIVE} there stays a 200 no-op.
 */
@Component
public class ConversationLifecycle {

	/** Every command a conversation accepts; the last two are message commands, not lifecycle commands. */
	public enum Command {

		RESOLVE, REOPEN, MARK_PENDING, ARCHIVE, UNARCHIVE, CONTACT_MESSAGE, AGENT_MESSAGE

	}

	/** What a transition does to the assignment projection; the service resolves it against the current state. */
	public enum AssignmentEffect {

		/** Leave {@code assignment_state} alone. */
		UNCHANGED,

		/** Resolve or archive: release any held capacity ({@code RELEASE_PENDING}) unless already free. */
		RELEASE_IF_HELD,

		/** Reopen: the allocation record must be requeued or kept ({@code REOPEN_PENDING}). */
		REOPEN

	}

	/**
	 * Result of a command.
	 *
	 * @param to target lifecycle status
	 * @param noOp nothing to write (a satisfied command, or a message that leaves the conversation as it is)
	 * @param stateWrite whether the conversation row needs a state write (version + 1 and one event)
	 * @param change the outbox {@code change} value when {@code stateWrite}
	 * @param stateEventType audit event type when {@code stateWrite}
	 * @param archived target archive marker when {@code stateWrite}; {@code null} keeps the current marker
	 * @param resolvedNow whether {@code resolved_at} must be set to now (T1)
	 * @param assignmentEffect what the transition does to the assignment projection
	 */
	public record Outcome(ConversationStatus to, boolean noOp, boolean stateWrite, ConversationChange change,
			String stateEventType, Boolean archived, boolean resolvedNow, AssignmentEffect assignmentEffect) {
	}

	/**
	 * True when the command's target already holds, which makes it a 200 no-op: status {@code RESOLVED} for
	 * {@code RESOLVE}, {@code OPEN} for {@code REOPEN}, {@code PENDING} for {@code MARK_PENDING}, archived for
	 * {@code ARCHIVE}, not archived for {@code UNARCHIVE}. On an archived conversation only {@code RESOLVE} (already
	 * resolved) and {@code ARCHIVE} count as satisfied (R-P8, R-P31 M14).
	 */
	public boolean isSatisfied(ConversationStatus from, boolean archived, Command command) {
		return switch (command) {
			case RESOLVE -> from == ConversationStatus.RESOLVED;
			case ARCHIVE -> archived;
			case REOPEN -> !archived && from == ConversationStatus.OPEN;
			case MARK_PENDING -> !archived && from == ConversationStatus.PENDING;
			case UNARCHIVE -> !archived;
			case CONTACT_MESSAGE, AGENT_MESSAGE -> false;
		};
	}

	public Outcome apply(ConversationStatus from, boolean archived, Command command) {
		if (archived) {
			return applyArchived(from, command);
		}
		return switch (command) {
			case RESOLVE -> switch (from) {
				case OPEN, PENDING -> new Outcome(ConversationStatus.RESOLVED, false, true, ConversationChange.RESOLVED,
						"RESOLVED", null, true, AssignmentEffect.RELEASE_IF_HELD);
				case RESOLVED -> noOp(from);
			};
			case REOPEN -> switch (from) {
				case OPEN -> noOp(from);
				case PENDING -> state(ConversationStatus.OPEN, ConversationChange.REOPENED, "REOPENED",
						AssignmentEffect.UNCHANGED);
				case RESOLVED -> state(ConversationStatus.OPEN, ConversationChange.REOPENED, "REOPENED",
						AssignmentEffect.REOPEN);
			};
			case MARK_PENDING -> switch (from) {
				case OPEN -> state(ConversationStatus.PENDING, ConversationChange.PENDING, "PENDING",
						AssignmentEffect.UNCHANGED);
				case PENDING -> noOp(from);
				case RESOLVED -> throw new ConversationConflictException("INVALID_TRANSITION");
			};
			case ARCHIVE -> new Outcome(from, false, true, ConversationChange.ARCHIVED, "ARCHIVED", Boolean.TRUE, false,
					AssignmentEffect.RELEASE_IF_HELD);
			case UNARCHIVE -> noOp(from);
			case CONTACT_MESSAGE -> switch (from) {
				case OPEN -> accepted(from);
				case PENDING -> state(ConversationStatus.OPEN, ConversationChange.REOPENED, "REOPENED_BY_CONTACT",
						AssignmentEffect.UNCHANGED);
				case RESOLVED -> state(ConversationStatus.OPEN, ConversationChange.REOPENED, "REOPENED_BY_CONTACT",
						AssignmentEffect.REOPEN);
			};
			case AGENT_MESSAGE -> switch (from) {
				case OPEN, PENDING -> accepted(from);
				case RESOLVED -> throw new ConversationConflictException("CONVERSATION_RESOLVED");
			};
		};
	}

	private Outcome applyArchived(ConversationStatus from, Command command) {
		return switch (command) {
			case RESOLVE -> {
				if (from == ConversationStatus.RESOLVED) {
					yield noOp(from);
				}
				throw new ConversationConflictException("CONVERSATION_ARCHIVED");
			}
			case ARCHIVE -> noOp(from);
			case REOPEN, MARK_PENDING -> throw new ConversationConflictException("CONVERSATION_ARCHIVED");
			case UNARCHIVE -> new Outcome(from, false, true, ConversationChange.UNARCHIVED, "UNARCHIVED", Boolean.FALSE,
					false,
					(from == ConversationStatus.RESOLVED) ? AssignmentEffect.UNCHANGED : AssignmentEffect.REOPEN);
			case CONTACT_MESSAGE, AGENT_MESSAGE -> throw new ConversationConflictException("CONVERSATION_ARCHIVED");
		};
	}

	private static Outcome state(ConversationStatus to, ConversationChange change, String stateEventType,
			AssignmentEffect effect) {
		return new Outcome(to, false, true, change, stateEventType, null, false, effect);
	}

	private static Outcome accepted(ConversationStatus from) {
		return new Outcome(from, false, false, null, null, null, false, AssignmentEffect.UNCHANGED);
	}

	private static Outcome noOp(ConversationStatus from) {
		return new Outcome(from, true, false, null, null, null, false, AssignmentEffect.UNCHANGED);
	}

}
