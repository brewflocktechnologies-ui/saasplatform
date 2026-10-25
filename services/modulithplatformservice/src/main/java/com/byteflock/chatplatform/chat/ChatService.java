package com.byteflock.chatplatform.chat;

import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.chat.ChatDtos.Conversation;
import com.byteflock.chatplatform.chat.ChatDtos.ConversationCreatedEvent;
import com.byteflock.chatplatform.chat.ChatDtos.Message;
import com.byteflock.chatplatform.chat.ChatDtos.MessageCreatedEvent;
import com.byteflock.chatplatform.chat.ChatDtos.SendMessageResponse;
import com.byteflock.chatplatform.realtime.RealtimePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class ChatService {

	static final int MAX_MESSAGE_LENGTH = 4000;

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

	private final ChatRepository repository;

	private final RealtimePublisher publisher;

	private final TransactionTemplate tx;

	private final MeterRegistry meters;

	ChatService(ChatRepository repository, RealtimePublisher publisher, TransactionTemplate tx, MeterRegistry meters) {
		this.repository = repository;
		this.publisher = publisher;
		this.tx = tx;
		this.meters = meters;
	}

	record EnsuredConversation(Conversation conversation, boolean created) {
	}

	/** Get-or-create the single OPEN demo conversation of {@code userone}, assigned to {@code agentbob}. */
	EnsuredConversation ensureDemoConversation() {
		boolean created = this.repository
			.insertOpenConversationIfAbsent(DemoActor.USERONE.id, DemoActor.AGENTBOB.id)
			.isPresent();
		Conversation conversation = this.repository.findOpenByVisitor(DemoActor.USERONE.id)
			.orElseThrow(() -> new IllegalStateException("open demo conversation vanished"));
		if (created) {
			log.info("Demo conversation created conversation={}", conversation.id());
			this.publisher.publish(ChatChannels.INBOX, new ConversationCreatedEvent(conversation));
		}
		return new EnsuredConversation(conversation, created);
	}

	List<Conversation> inbox(String actorId) {
		DemoActor actor = DemoActor.resolve(actorId);
		if (!actor.isAgent()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the demo agent has an inbox");
		}
		return this.repository.findByAgent(actor.id);
	}

	List<Message> history(UUID conversationId, String actorId) {
		DemoActor actor = DemoActor.resolve(actorId);
		authorize(actor, conversationId);
		return this.repository.findMessages(conversationId);
	}

	/**
	 * Validate, persist (commit), then publish. A DB failure throws before any publication; a publish failure after
	 * commit keeps the message and is reported as {@code realtimeDelivery=FAILED}.
	 */
	SendMessageResponse send(UUID conversationId, String actorId, String text) {
		DemoActor actor = DemoActor.resolve(actorId);
		String body = normalize(text);
		authorize(actor, conversationId);

		Message message = this.tx.execute((status) -> {
			Message saved = this.repository.insertMessage(conversationId, actor.id, actor.role, body);
			this.repository.touchConversation(conversationId, saved.createdAt());
			return saved;
		});
		this.meters.counter("chat.messages.persisted", "role", actor.role).increment();
		log.info("Message persisted conversation={} message={} sender={}", conversationId, message.id(), actor.id);

		MessageCreatedEvent event = new MessageCreatedEvent(message);
		boolean live = this.publisher.publish(ChatChannels.conversation(conversationId), event);
		// Inbox refresh signal for the agent; failure here does not affect the conversation delivery result.
		this.publisher.publish(ChatChannels.INBOX, event);
		if (!live) {
			log.warn("Message persisted but live delivery failed conversation={} message={}", conversationId,
					message.id());
		}
		return new SendMessageResponse(message, live ? "PUBLISHED" : "FAILED");
	}

	/** 404 unknown conversation, 403 actor not a participant. Used for history, send and subscription tokens. */
	Conversation authorize(DemoActor actor, UUID conversationId) {
		Conversation conversation = this.repository.findById(conversationId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
		boolean member = actor.isAgent() ? actor.id.equals(conversation.assignedAgentId())
				: actor.id.equals(conversation.visitorId());
		if (!member) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Actor is not a participant of this conversation");
		}
		return conversation;
	}

	static String normalize(String text) {
		String body = (text == null) ? "" : text.strip();
		if (body.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must not be empty");
		}
		if (body.codePointCount(0, body.length()) > MAX_MESSAGE_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"text must be at most " + MAX_MESSAGE_LENGTH + " characters");
		}
		return body;
	}

}
