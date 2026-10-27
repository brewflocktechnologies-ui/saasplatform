package com.byteflock.chatplatform.conversation;

/** How {@code POST …/assign} asks the allocation service for an assignment (Plan 04). */
public enum AssignMode {

	/** Let the allocation policy choose an agent. */
	AUTO,

	/** The calling agent takes the conversation. */
	CLAIM,

	/** Reassign a conversation that is already assigned. */
	REASSIGN

}
