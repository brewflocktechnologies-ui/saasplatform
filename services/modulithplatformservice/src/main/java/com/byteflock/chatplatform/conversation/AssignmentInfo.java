package com.byteflock.chatplatform.conversation;

/**
 * Assignment information visible to a caller. Agent callers get the full {@link AssignmentView}; contact callers and
 * every realtime event payload get {@link ContactAssignmentView} with exactly {@code state} and {@code agentId}
 * (Plan 01 rulings R-P3, R-P29) because the conversation channel is shared with the visitor.
 */
public sealed interface AssignmentInfo permits AssignmentView, ContactAssignmentView {

	AssignmentState state();

	String agentId();

}
