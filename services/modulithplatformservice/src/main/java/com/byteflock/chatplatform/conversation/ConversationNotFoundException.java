package com.byteflock.chatplatform.conversation;

/** The conversation does not exist (or is not visible in the caller's scope, in which case 403 is used instead). */
public class ConversationNotFoundException extends RuntimeException {

	public ConversationNotFoundException() {
		super("conversation not found");
	}

}
