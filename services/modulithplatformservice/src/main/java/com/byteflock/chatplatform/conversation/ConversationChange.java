package com.byteflock.chatplatform.conversation;

/** The {@code data.change} value of a {@code conversation.created}/{@code conversation.updated} event (D12). */
public enum ConversationChange {

	ASSIGNED,

	RESOLVED,

	REOPENED,

	PENDING,

	ARCHIVED,

	UNARCHIVED,

	ASSIGNMENT_QUEUED,

	ASSIGNMENT_FAILED,

	/** Any projection change that fits none of the other values, e.g. a release to {@code NONE} (R-P23). */
	ASSIGNMENT_UPDATED

}
