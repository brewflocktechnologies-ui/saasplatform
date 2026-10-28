package com.byteflock.chatplatform.conversation;

import java.util.stream.Stream;

import com.byteflock.chatplatform.conversation.internal.ConversationLifecycle;
import com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.AssignmentEffect;
import com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command;
import com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.byteflock.chatplatform.conversation.ConversationStatus.OPEN;
import static com.byteflock.chatplatform.conversation.ConversationStatus.PENDING;
import static com.byteflock.chatplatform.conversation.ConversationStatus.RESOLVED;
import static com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command.AGENT_MESSAGE;
import static com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command.ARCHIVE;
import static com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command.CONTACT_MESSAGE;
import static com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command.MARK_PENDING;
import static com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command.REOPEN;
import static com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command.RESOLVE;
import static com.byteflock.chatplatform.conversation.internal.ConversationLifecycle.Command.UNARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * All 42 cells of the transition table in {@code docs/architecture/domain-model.md} section 5.2
 * (3 statuses × 2 archive states × 7 commands), plus the check-order rules of section 5.1.
 */
class ConversationLifecycleTest {

	private final ConversationLifecycle lifecycle = new ConversationLifecycle();

	@ParameterizedTest(name = "{0} archived={1} {2} → {3}")
	@MethodSource("cases")
	void appliesTheDocumentedTransition(ConversationStatus from, boolean archived, Command command, Outcome expected) {
		Outcome outcome = this.lifecycle.apply(from, archived, command);

		assertThat(outcome.to()).isEqualTo(expected.to());
		assertThat(outcome.noOp()).isEqualTo(expected.noOp());
		assertThat(outcome.stateWrite()).isEqualTo(expected.stateWrite());
		assertThat(outcome.change()).isEqualTo(expected.change());
		assertThat(outcome.stateEventType()).isEqualTo(expected.stateEventType());
		assertThat(outcome.archived()).isEqualTo(expected.archived());
		assertThat(outcome.resolvedNow()).isEqualTo(expected.resolvedNow());
		assertThat(outcome.assignmentEffect()).isEqualTo(expected.assignmentEffect());
	}

	@ParameterizedTest(name = "{0} archived={1} {2} → conflict {3}")
	@MethodSource("conflicts")
	void rejectsTheDocumentedConflicts(ConversationStatus from, boolean archived, Command command, String code) {
		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.lifecycle.apply(from, archived, command))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo(code));
	}

	@Test
	void satisfiedCommandsAreNoOpsAndArchivedStatusCommandsAreNot() {
		assertThat(this.lifecycle.isSatisfied(RESOLVED, false, RESOLVE)).isTrue();
		assertThat(this.lifecycle.isSatisfied(RESOLVED, true, RESOLVE)).isTrue();
		assertThat(this.lifecycle.isSatisfied(RESOLVED, true, ARCHIVE)).isTrue();
		assertThat(this.lifecycle.isSatisfied(OPEN, false, REOPEN)).isTrue();
		assertThat(this.lifecycle.isSatisfied(PENDING, false, MARK_PENDING)).isTrue();
		assertThat(this.lifecycle.isSatisfied(OPEN, true, REOPEN)).isFalse();
		assertThat(this.lifecycle.isSatisfied(PENDING, true, MARK_PENDING)).isFalse();
		assertThat(this.lifecycle.isSatisfied(OPEN, true, UNARCHIVE)).isFalse();
		assertThat(this.lifecycle.isSatisfied(OPEN, false, CONTACT_MESSAGE)).isFalse();
	}

	private static ArgumentSet row(ConversationStatus from, boolean archived, Command command, Outcome expected) {
		return new ArgumentSet(from, archived, command, expected);
	}

	/** 42 rows: the non-archived table of section 5.2 first, then the archived one. */
	static Stream<Arguments> cases() {
		return Stream.of(
				// Not archived.
				row(OPEN, false, RESOLVE, write(RESOLVED, ConversationChange.RESOLVED, "RESOLVED", null, true,
						AssignmentEffect.RELEASE_IF_HELD)),
				row(PENDING, false, RESOLVE, write(RESOLVED, ConversationChange.RESOLVED, "RESOLVED", null, true,
						AssignmentEffect.RELEASE_IF_HELD)),
				row(RESOLVED, false, RESOLVE, noOp(RESOLVED)),
				row(OPEN, false, REOPEN, noOp(OPEN)),
				row(PENDING, false, REOPEN, write(OPEN, ConversationChange.REOPENED, "REOPENED", null, false,
						AssignmentEffect.UNCHANGED)),
				row(RESOLVED, false, REOPEN, write(OPEN, ConversationChange.REOPENED, "REOPENED", null, false,
						AssignmentEffect.REOPEN)),
				row(OPEN, false, MARK_PENDING, write(PENDING, ConversationChange.PENDING, "PENDING", null, false,
						AssignmentEffect.UNCHANGED)),
				row(PENDING, false, MARK_PENDING, noOp(PENDING)),
				row(OPEN, false, ARCHIVE, write(OPEN, ConversationChange.ARCHIVED, "ARCHIVED", Boolean.TRUE, false,
						AssignmentEffect.RELEASE_IF_HELD)),
				row(PENDING, false, ARCHIVE, write(PENDING, ConversationChange.ARCHIVED, "ARCHIVED", Boolean.TRUE, false,
						AssignmentEffect.RELEASE_IF_HELD)),
				row(RESOLVED, false, ARCHIVE, write(RESOLVED, ConversationChange.ARCHIVED, "ARCHIVED", Boolean.TRUE,
						false, AssignmentEffect.RELEASE_IF_HELD)),
				row(OPEN, false, UNARCHIVE, noOp(OPEN)),
				row(PENDING, false, UNARCHIVE, noOp(PENDING)),
				row(RESOLVED, false, UNARCHIVE, noOp(RESOLVED)),
				row(OPEN, false, CONTACT_MESSAGE, accepted(OPEN)),
				row(PENDING, false, CONTACT_MESSAGE, write(OPEN, ConversationChange.REOPENED, "REOPENED_BY_CONTACT", null,
						false, AssignmentEffect.UNCHANGED)),
				row(RESOLVED, false, CONTACT_MESSAGE, write(OPEN, ConversationChange.REOPENED, "REOPENED_BY_CONTACT",
						null, false, AssignmentEffect.REOPEN)),
				row(OPEN, false, AGENT_MESSAGE, accepted(OPEN)),
				row(PENDING, false, AGENT_MESSAGE, accepted(PENDING)),
				// Archived.
				row(RESOLVED, true, RESOLVE, noOp(RESOLVED)),
				row(OPEN, true, ARCHIVE, noOp(OPEN)),
				row(PENDING, true, ARCHIVE, noOp(PENDING)),
				row(RESOLVED, true, ARCHIVE, noOp(RESOLVED)),
				row(OPEN, true, UNARCHIVE, write(OPEN, ConversationChange.UNARCHIVED, "UNARCHIVED", Boolean.FALSE, false,
						AssignmentEffect.REOPEN)),
				row(PENDING, true, UNARCHIVE, write(PENDING, ConversationChange.UNARCHIVED, "UNARCHIVED", Boolean.FALSE,
						false, AssignmentEffect.REOPEN)),
				row(RESOLVED, true, UNARCHIVE, write(RESOLVED, ConversationChange.UNARCHIVED, "UNARCHIVED", Boolean.FALSE,
						false, AssignmentEffect.UNCHANGED)))
			.map(ArgumentSet::arguments);
	}

	/** The remaining 16 cells are conflicts (42 = 26 applied + 16 rejected). */
	static Stream<Arguments> conflicts() {
		return Stream.of(
				// Not archived: forbidden status transitions and an agent message into a resolved conversation.
				new ArgumentSet(RESOLVED, false, MARK_PENDING, "INVALID_TRANSITION"),
				new ArgumentSet(RESOLVED, false, AGENT_MESSAGE, "CONVERSATION_RESOLVED"),
				// Archived: every status command except a satisfied RESOLVE/ARCHIVE, and every message.
				new ArgumentSet(OPEN, true, RESOLVE, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(PENDING, true, RESOLVE, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(OPEN, true, REOPEN, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(PENDING, true, REOPEN, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(RESOLVED, true, REOPEN, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(OPEN, true, MARK_PENDING, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(PENDING, true, MARK_PENDING, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(RESOLVED, true, MARK_PENDING, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(OPEN, true, CONTACT_MESSAGE, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(PENDING, true, CONTACT_MESSAGE, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(RESOLVED, true, CONTACT_MESSAGE, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(OPEN, true, AGENT_MESSAGE, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(PENDING, true, AGENT_MESSAGE, "CONVERSATION_ARCHIVED"),
				new ArgumentSet(RESOLVED, true, AGENT_MESSAGE, "CONVERSATION_ARCHIVED"))
			.map((set) -> Arguments.of(set.from(), set.archived(), set.command(), set.code()));
	}

	record ArgumentSet(ConversationStatus from, boolean archived, Command command, Outcome expected, String code) {

		ArgumentSet(ConversationStatus from, boolean archived, Command command, Outcome expected) {
			this(from, archived, command, expected, (String) null);
		}

		ArgumentSet(ConversationStatus from, boolean archived, Command command, String code) {
			this(from, archived, command, null, code);
		}

		Arguments arguments() {
			return Arguments.of(this.from, this.archived, this.command, this.expected);
		}

	}

	private static Outcome noOp(ConversationStatus from) {
		return new Outcome(from, true, false, null, null, null, false, AssignmentEffect.UNCHANGED);
	}

	private static Outcome accepted(ConversationStatus from) {
		return new Outcome(from, false, false, null, null, null, false, AssignmentEffect.UNCHANGED);
	}

	private static Outcome write(ConversationStatus to, ConversationChange change, String event, Boolean archived,
			boolean resolvedNow, AssignmentEffect effect) {
		return new Outcome(to, false, true, change, event, archived, resolvedNow, effect);
	}

}
