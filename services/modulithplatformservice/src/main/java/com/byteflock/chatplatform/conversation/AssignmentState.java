package com.byteflock.chatplatform.conversation;

/**
 * The platform's projection of the allocation service assignment state (D8, domain-model.md section 6). The allocation
 * service owns the assignment record; this is only a view of where the request stands.
 */
public enum AssignmentState {

	/** Nothing requested. */
	NONE,

	/** Durable request to allocate; the only state the worker dispatches from. */
	REQUESTED,

	/** The allocation service holds the conversation in its queue; the worker only polls. */
	QUEUED,

	/** The allocation service assigned {@code assigned_agent_id}. */
	ASSIGNED,

	/** Resolve or archive must reach the allocation service to free capacity. */
	RELEASE_PENDING,

	/** The conversation was reopened or unarchived; the allocation record must be requeued or kept. */
	REOPEN_PENDING,

	/** The request needs a person: a rejection, a configuration fault or too many setup errors. */
	FAILED

}
