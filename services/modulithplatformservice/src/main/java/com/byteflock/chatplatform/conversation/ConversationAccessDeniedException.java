package com.byteflock.chatplatform.conversation;

/**
 * The caller may not see this conversation: the id exists, but in another tenant, another inbox or for another contact
 * (403). A non-existent id is {@link ConversationNotFoundException} (404) instead.
 */
public class ConversationAccessDeniedException extends RuntimeException {

	public ConversationAccessDeniedException() {
		super("conversation is not visible to this caller");
	}

}
