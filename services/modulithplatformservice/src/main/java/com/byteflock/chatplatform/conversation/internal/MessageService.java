package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.Channels;
import com.byteflock.chatplatform.conversation.ConversationConflictException;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.conversation.MessageView;
import com.byteflock.chatplatform.conversation.SendMessageRequest;
import com.byteflock.chatplatform.outbox.OutboxAppender;
import com.byteflock.chatplatform.tenancy.Caller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durable send path (D4, D5, ADR-0006): one transaction that locks the conversation row, applies the lifecycle
 * policy, assigns the next sequence, inserts the message, touches activity, upserts the agent participant, advances the
 * sender's own read cursor, writes the state event and appends the outbox rows.
 * <p>
 * Idempotency is checked after the participant check and before the lifecycle policy, so the same
 * {@code clientMessageId} and body return the original message (200) even if the conversation was resolved or archived
 * since (Plan 01 ruling R-P24).
 */
@Service
public class MessageService {

	private final TransactionTemplate tx;

	private final ConversationAccess access;

	private final ConversationRepository conversations;

	private final MessageRepository messages;

	private final ParticipantRepository participants;

	private final ReadCursorRepository cursors;

	private final StateEventRepository stateEvents;

	private final ConversationLifecycle lifecycle;

	private final OutboxAppender outbox;

	private final Clock clock;

	private final int maxMessageLength;

	MessageService(TransactionTemplate tx, ConversationAccess access, ConversationRepository conversations,
			MessageRepository messages, ParticipantRepository participants, ReadCursorRepository cursors,
			StateEventRepository stateEvents, ConversationLifecycle lifecycle, OutboxAppender outbox, Clock clock,
			@Value("${app.limits.message-length}") int maxMessageLength) {
		this.tx = tx;
		this.access = access;
		this.conversations = conversations;
		this.messages = messages;
		this.participants = participants;
		this.cursors = cursors;
		this.stateEvents = stateEvents;
		this.lifecycle = lifecycle;
		this.outbox = outbox;
		this.clock = clock;
		this.maxMessageLength = maxMessageLength;
	}

	/** Result of a send: the message and whether this call created it ({@code false} = idempotent replay). */
	public record SendResult(MessageView message, boolean created) {
	}

	public SendResult send(Caller caller, UUID conversationId, SendMessageRequest request) {
		String clientMessageId = validateClientMessageId((request != null) ? request.clientMessageId() : null);
		String body = normalizeBody((request != null) ? request.body() : null);
		String fingerprint = Fingerprints.sha256Hex(body);
		return this.tx.execute((status) -> sendInTransaction(caller, conversationId, clientMessageId, body, fingerprint));
	}

	private SendResult sendInTransaction(Caller caller, UUID conversationId, String clientMessageId, String body,
			String fingerprint) {
		ConversationRow row = this.access.lockForWrite(caller, conversationId);
		String senderType = caller.type().name();
		String senderId = caller.principalId();
		var existing = this.messages.findByClientMessageId(conversationId, senderType, senderId, clientMessageId);
		if (existing.isPresent()) {
			if (!existing.get().fingerprint().equals(fingerprint)) {
				throw new ConversationConflictException("CLIENT_MESSAGE_ID_REUSED");
			}
			return new SendResult(existing.get().view(), false);
		}
		ConversationLifecycle.Command command = caller.isContact() ? ConversationLifecycle.Command.CONTACT_MESSAGE
				: ConversationLifecycle.Command.AGENT_MESSAGE;
		ConversationLifecycle.Outcome outcome = this.lifecycle.apply(row.status(), row.archived(), command);
		if (outcome.stateWrite()) {
			AssignmentState target = AssignmentTransitions.target(row.assignmentState(), outcome.assignmentEffect());
			ConversationRepository.StateWrite write = new ConversationRepository.StateWrite(row.id(), row.tenantId(),
					row.version(), outcome.to(), OffsetDateTime.now(this.clock), outcome.resolvedNow(),
					outcome.archived(), null, null, target,
					AssignmentTransitions.movingOutOfFailed(row.assignmentState()));
			int updated;
			try {
				updated = this.conversations.updateState(write);
			}
			catch (org.springframework.dao.DataIntegrityViolationException ex) {
				// A contact message that would reopen a second active conversation for the same contact.
				throw new ConversationConflictException("CONVERSATION_ACTIVE_EXISTS");
			}
			if (updated == 0) {
				throw new ConversationConflictException("STALE_VERSION");
			}
		}
		long sequence = this.conversations.bumpSequence(conversationId);
		MessageView message = this.messages.insert(row.tenantId(), conversationId, sequence, senderType, senderId, body,
				clientMessageId, fingerprint);
		this.conversations.touchActivity(conversationId, message.createdAt(), preview(body));
		this.participants.ensure(conversationId, row.tenantId(), senderType, senderId);
		// The sender's own message is never unread for the sender (R12).
		this.cursors.advance(conversationId, row.tenantId(), senderType, senderId, sequence);
		if (outcome.stateWrite()) {
			this.stateEvents.insert(conversationId, row.tenantId(), outcome.stateEventType(), senderType, senderId,
					row.status().name(), outcome.to().name(), null);
			appendConversationUpdated(caller, row, outcome.change());
		}
		appendMessageCreated(row, message);
		return new SendResult(message, true);
	}

	private void appendConversationUpdated(Caller caller, ConversationRow row,
			com.byteflock.chatplatform.conversation.ConversationChange change) {
		ConversationView view = this.access.view(caller, row.id());
		this.outbox.append(row.tenantId(), "conversation", row.id().toString(), "conversation.updated",
				List.of(Channels.conversation(row.id()), Channels.inbox(row.tenantId(), row.inboxId())),
				EventPayloads.conversationUpdated(view, change));
	}

	private void appendMessageCreated(ConversationRow row, MessageView message) {
		this.outbox.append(row.tenantId(), "conversation", row.id().toString(), "message.created",
				List.of(Channels.conversation(row.id()), Channels.inbox(row.tenantId(), row.inboxId())),
				EventPayloads.messageCreated(message));
	}

	/** First 120 characters of the body, the inbox preview (never more, so a preview cannot leak a long body). */
	static String preview(String body) {
		return (body.length() <= 120) ? body : body.substring(0, 120);
	}

	private static String validateClientMessageId(String value) {
		if (value == null || value.isBlank() || value.length() > 64) {
			throw new IllegalArgumentException("clientMessageId must be 1 to 64 characters");
		}
		return value;
	}

	private String normalizeBody(String value) {
		String body = (value != null) ? value.strip() : "";
		if (body.isEmpty()) {
			throw new IllegalArgumentException("body must not be empty");
		}
		if (body.codePointCount(0, body.length()) > this.maxMessageLength) {
			throw new IllegalArgumentException("body must be at most " + this.maxMessageLength + " characters");
		}
		return body;
	}

}
