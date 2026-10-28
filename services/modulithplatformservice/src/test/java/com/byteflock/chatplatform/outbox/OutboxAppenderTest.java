package com.byteflock.chatplatform.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.byteflock.chatplatform.outbox.internal.OutboxRecord;
import com.byteflock.chatplatform.outbox.internal.OutboxRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The outbox appender: it must run inside the change transaction, it must build the envelope the publisher stores, and it
 * must stamp the current trace id (Plan 02 T3, Plan 03 consumes the rows).
 */
class OutboxAppenderTest {

	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);

	final OutboxRepository repository = mock(OutboxRepository.class);

	final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void appendOutsideTransactionThrows() {
		OutboxAppender appender = appender(tracer(null));

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> appender.append("tenant-a", "conversation", UUID.randomUUID().toString(),
					"message.created", List.of("chat:conversation:x"), Map.of("message", "m")))
			.withMessageContaining("active transaction");
	}

	@Test
	void appendBuildsEnvelope() {
		UUID eventId = UUID.randomUUID();
		UUID conversationId = UUID.randomUUID();
		given(this.repository.nextEventId()).willReturn(eventId);
		OutboxAppender appender = appender(tracer(null));

		UUID returned = inTransaction(() -> appender.append("tenant-a", "conversation", conversationId.toString(),
				"message.created", List.of("chat:conversation:" + conversationId, "chat:inbox:tenant-a:inbox"),
				Map.of("message", Map.of("id", "m-1"))));

		assertThat(returned).isEqualTo(eventId);
		OutboxRecord record = captureRecord();
		assertThat(record.eventId()).isEqualTo(eventId);
		assertThat(record.tenantId()).isEqualTo("tenant-a");
		assertThat(record.aggregateType()).isEqualTo("conversation");
		assertThat(record.aggregateId()).isEqualTo(conversationId.toString());
		assertThat(record.eventType()).isEqualTo("message.created");
		assertThat(record.schemaVersion()).isEqualTo(1);
		assertThat(record.channels()).containsExactly("chat:conversation:" + conversationId, "chat:inbox:tenant-a:inbox");
		assertThat(record.nextAttemptAt().toInstant()).isEqualTo(CLOCK.instant());
		assertThat(record.traceId()).isNull();

		JsonNode payload = this.mapper.readTree(record.payload());
		assertThat(payload.get("eventId").asString()).isEqualTo(eventId.toString());
		assertThat(payload.get("eventType").asString()).isEqualTo("message.created");
		assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
		assertThat(payload.get("tenantId").asString()).isEqualTo("tenant-a");
		assertThat(payload.get("conversationId").asString()).isEqualTo(conversationId.toString());
		assertThat(payload.get("occurredAt").asString()).isEqualTo("2026-10-27T10:00:00Z");
		assertThat(payload.at("/data/message/id").asString()).isEqualTo("m-1");
	}

	@Test
	void appendStoresCurrentTraceId() {
		given(this.repository.nextEventId()).willReturn(UUID.randomUUID());
		Tracer tracer = mock(Tracer.class);
		Span span = mock(Span.class);
		TraceContext context = mock(TraceContext.class);
		given(tracer.currentSpan()).willReturn(span);
		given(span.context()).willReturn(context);
		given(context.traceId()).willReturn("abc");
		OutboxAppender appender = appender(tracer(tracer));

		inTransaction(() -> appender.append("tenant-a", "conversation", UUID.randomUUID().toString(),
				"conversation.updated", List.of("chat:conversation:x"), Map.of("change", "RESOLVED")));

		assertThat(captureRecord().traceId()).isEqualTo("abc");
	}

	private OutboxAppender appender(ObjectProvider<Tracer> tracer) {
		return new OutboxAppender(this.repository, this.mapper, CLOCK, tracer);
	}

	private OutboxRecord captureRecord() {
		ArgumentCaptor<OutboxRecord> captor = ArgumentCaptor.forClass(OutboxRecord.class);
		verify(this.repository).insert(captor.capture());
		return captor.getValue();
	}

	/** Runs the append with the transaction flag set, which is what {@code TransactionSynchronizationManager} exposes. */
	private static <T> T inTransaction(java.util.function.Supplier<T> action) {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		try {
			return action.get();
		}
		finally {
			TransactionSynchronizationManager.clear();
		}
	}

	private static ObjectProvider<Tracer> tracer(Tracer tracer) {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		if (tracer != null) {
			factory.registerSingleton("tracer", tracer);
		}
		return factory.getBeanProvider(Tracer.class);
	}

}
