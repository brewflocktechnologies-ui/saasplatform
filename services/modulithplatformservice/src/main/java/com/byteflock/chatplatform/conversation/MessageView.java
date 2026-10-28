package com.byteflock.chatplatform.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A message as returned by the API. Bodies are never edited in the MVP (columns reserved for NEXT). */
public record MessageView(UUID id, UUID conversationId, long sequence, String senderType, String senderId,
		String messageType, String body, String clientMessageId, OffsetDateTime createdAt, OffsetDateTime editedAt,
		OffsetDateTime deletedAt) {
}
