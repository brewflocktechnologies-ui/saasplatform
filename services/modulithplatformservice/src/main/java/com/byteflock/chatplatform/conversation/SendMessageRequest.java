package com.byteflock.chatplatform.conversation;

/**
 * Request body of a send. {@code clientMessageId} is mandatory for browser clients and scopes idempotency to
 * {@code (conversation, senderType, senderId)} (D5).
 */
public record SendMessageRequest(String clientMessageId, String body) {
}
