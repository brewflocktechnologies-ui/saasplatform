package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.Channels;
import com.byteflock.chatplatform.outbox.EventEnvelope;
import com.byteflock.chatplatform.realtime.RealtimePublisher;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ephemeral typing indicators (D12): no database row, no outbox row — a direct Centrifugo publication to the
 * conversation channel, authorized exactly like a message and limited to one signal per {@code typing-interval} per
 * principal and conversation. A failed publication is logged and still answers 202: typing is best effort.
 */
@Service
public class TypingService {

	private static final Logger log = LoggerFactory.getLogger(TypingService.class);

	private final ConversationAccess access;

	private final RateLimiter rateLimiter;

	private final RealtimePublisher publisher;

	private final Clock clock;

	private final Duration interval;

	TypingService(ConversationAccess access, RateLimiter rateLimiter, RealtimePublisher publisher, Clock clock,
			@Value("${app.rate-limits.typing-interval}") Duration interval) {
		this.access = access;
		this.rateLimiter = rateLimiter;
		this.publisher = publisher;
		this.clock = clock;
		this.interval = interval;
	}

	public void typing(Caller caller, UUID conversationId, boolean typing) {
		this.access.requireReadable(caller, conversationId);
		String bucket = "typing:" + caller.tenantId() + ":" + caller.type().name() + ":" + caller.principalId() + ":"
				+ conversationId;
		if (!this.rateLimiter.tryAcquireInterval(bucket, this.interval)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("participantType", caller.type().name());
		data.put("participantId", caller.principalId());
		data.put("typing", typing);
		EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "typing", 1, caller.tenantId(), conversationId,
				OffsetDateTime.now(this.clock), data);
		if (!this.publisher.publish(Channels.conversation(conversationId), envelope)) {
			log.debug("Typing indicator not delivered conversation={} principal={}", conversationId,
					caller.principalId());
		}
	}

}
