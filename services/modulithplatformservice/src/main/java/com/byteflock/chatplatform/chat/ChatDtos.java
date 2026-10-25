package com.byteflock.chatplatform.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Public API/event DTOs of the chat module; shapes follow libs/contracts (OpenAPI + JSON Schema). */
final class ChatDtos {

	private ChatDtos() {
	}

	record Conversation(UUID id, String visitorId, String assignedAgentId, String status, OffsetDateTime createdAt,
			OffsetDateTime updatedAt, OffsetDateTime lastMessageAt, String lastMessagePreview) {
	}

	record Message(UUID id, UUID conversationId, String senderId, String senderRole, String body,
			OffsetDateTime createdAt) {
	}

	record SendMessageRequest(String actorId, String text) {
	}

	/** {@code realtimeDelivery}: PUBLISHED, or FAILED when the message is saved but the live event was not sent. */
	record SendMessageResponse(Message message, String realtimeDelivery) {
	}

	record MessageCreatedEvent(String type, UUID conversationId, Message message) {
		MessageCreatedEvent(Message message) {
			this("message.created", message.conversationId(), message);
		}
	}

	record ConversationCreatedEvent(String type, Conversation conversation) {
		ConversationCreatedEvent(Conversation conversation) {
			this("conversation.created", conversation);
		}
	}

}
