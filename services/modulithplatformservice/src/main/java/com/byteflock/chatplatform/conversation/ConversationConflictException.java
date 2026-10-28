package com.byteflock.chatplatform.conversation;

/**
 * A command violated a documented rule. {@link #code()} is the problem code the API returns, e.g.
 * {@code CONVERSATION_ARCHIVED}, {@code INVALID_TRANSITION}, {@code STALE_VERSION}, {@code CONVERSATION_RESOLVED},
 * {@code CLIENT_MESSAGE_ID_REUSED} or {@code CONVERSATION_ACTIVE_EXISTS}.
 */
public class ConversationConflictException extends RuntimeException {

	private final String code;

	public ConversationConflictException(String code) {
		super(code);
		this.code = code;
	}

	public ConversationConflictException(String code, String detail) {
		super(detail);
		this.code = code;
	}

	public String code() {
		return this.code;
	}

}
