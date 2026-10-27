package com.byteflock.chatplatform.conversation.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import com.byteflock.chatplatform.conversation.ConversationChange;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.conversation.MessageView;

/**
 * Realtime payloads of this plan (D12, R14): {@code conversation.created}/{@code conversation.updated} carry the
 * conversation without the per-caller fields, and {@code message.created} carries the message. Payload keys are inserted
 * in a stable order.
 */
final class EventPayloads {

	private EventPayloads() {
	}

	static Map<String, Object> conversationCreated(ConversationView view) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("conversation", EventConversation.of(view));
		return data;
	}

	static Map<String, Object> conversationUpdated(ConversationView view, ConversationChange change) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("conversation", EventConversation.of(view));
		data.put("change", change.name());
		return data;
	}

	static Map<String, Object> messageCreated(MessageView message) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("message", message);
		return data;
	}

}
