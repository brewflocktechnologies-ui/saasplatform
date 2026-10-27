package com.byteflock.chatplatform.conversation.internal;

import com.byteflock.chatplatform.conversation.AssignmentState;

/** Resolves the assignment effect of a lifecycle transition against the current projection (domain-model.md §5.3). */
final class AssignmentTransitions {

	private AssignmentTransitions() {
	}

	static AssignmentState target(AssignmentState current, ConversationLifecycle.AssignmentEffect effect) {
		return switch (effect) {
			case UNCHANGED -> current;
			case RELEASE_IF_HELD -> switch (current) {
				// A FAILED row must not hold engine capacity silently (Plan 01 ruling R-P17).
				case REQUESTED, QUEUED, ASSIGNED, REOPEN_PENDING, FAILED -> AssignmentState.RELEASE_PENDING;
				case NONE, RELEASE_PENDING -> current;
			};
			case REOPEN -> AssignmentState.REOPEN_PENDING;
		};
	}

	/** A move out of FAILED sets the next attempt to now instead of GREATEST, because FAILED's time is a poll schedule (R-P20). */
	static boolean movingOutOfFailed(AssignmentState current) {
		return current == AssignmentState.FAILED;
	}

}
