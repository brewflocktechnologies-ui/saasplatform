package com.byteflock.chatplatform.outbox;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.outbox.internal.OutboxRecord;
import com.byteflock.chatplatform.outbox.internal.OutboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Appends durable domain events to the outbox inside the caller's transaction (D6).
 * <p>
 * The appender must run in an active transaction: an event that is not committed together with its change could be
 * published for a rolled-back write, or lost for a committed one. {@code occurredAt} and {@code next_attempt_at} come
 * from the injected {@link Clock} so tests can control backoff and so a fresh row is never ahead of the publisher's
 * clock by more than the JVM/container offset.
 */
@Component
public class OutboxAppender {

	/** Schema version of the event payloads this plan produces (Plan 03 owns the schemas). */
	static final int SCHEMA_VERSION = 1;

	private final OutboxRepository repository;

	private final ObjectMapper mapper;

	private final Clock clock;

	private final ObjectProvider<Tracer> tracer;

	public OutboxAppender(OutboxRepository repository, ObjectMapper mapper, Clock clock,
			ObjectProvider<Tracer> tracer) {
		this.repository = repository;
		this.mapper = mapper;
		this.clock = clock;
		this.tracer = tracer;
	}

	/**
	 * Appends one event row; returns the envelope's {@code eventId}. Throws {@link IllegalStateException} when no
	 * transaction is active — callers must not "fix" that by opening their own transaction around a change that has
	 * already been committed.
	 */
	public UUID append(String tenantId, String aggregateType, String aggregateId, String eventType,
			List<String> channels, Object data) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("OutboxAppender.append must be called inside an active transaction");
		}
		UUID eventId = this.repository.nextEventId();
		OffsetDateTime occurredAt = OffsetDateTime.now(this.clock);
		UUID conversationId = "conversation".equals(aggregateType) ? UUID.fromString(aggregateId) : null;
		EventEnvelope envelope = new EventEnvelope(eventId, eventType, SCHEMA_VERSION, tenantId, conversationId,
				occurredAt, data);
		String payload = this.mapper.writeValueAsString(envelope);
		this.repository.insert(new OutboxRecord(eventId, tenantId, aggregateType, aggregateId, eventType, SCHEMA_VERSION,
				List.copyOf(channels), payload, occurredAt, traceId()));
		return eventId;
	}

	private String traceId() {
		Tracer current = this.tracer.getIfAvailable();
		Span span = (current != null) ? current.currentSpan() : null;
		return (span != null) ? span.context().traceId() : null;
	}

}
