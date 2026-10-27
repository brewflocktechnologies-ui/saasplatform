package com.byteflock.chatplatform.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.byteflock.chatplatform.CentrifugoStub;
import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.outbox.internal.OutboxHook;
import com.byteflock.chatplatform.outbox.internal.OutboxProperties;
import com.byteflock.chatplatform.outbox.internal.OutboxPublisher;
import com.byteflock.chatplatform.outbox.internal.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outbox publisher against real PostgreSQL and the Centrifugo stub: leased claims, publication, backoff, transport versus
 * rejection classification, parking, replay, concurrent workers, bounded batches and metrics (D6, ADR-0007, R-P15,
 * R-P27, R-P28, R-P30, R-P31 M16).
 */
class OutboxPublisherIT extends PlatformIntegrationTest {

	static final String TENANT = "tenant-outbox";

	/** The shared stub of the integration-test base. */
	final CentrifugoStub stub = CENTRIFUGO;

	@Autowired
	OutboxPublisher publisher;

	@Autowired
	OutboxRepository repository;

	@Autowired
	OutboxAppender appender;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	OutboxProperties properties;

	@Autowired
	OutboxHook hook;

	@Autowired
	MeterRegistry meters;

	@Test
	void publishesPendingEventsWithBroadcastAndMarksPublished() {
		UUID eventId = appendEvent();

		int processed = this.publisher.runOnce();

		assertThat(processed).isEqualTo(1);
		OutboxRepository.StoredEvent stored = stored(eventId);
		assertThat(stored.status()).isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(stored.publishedAt()).isNotNull();
		assertThat(this.jdbc.sql("SELECT locked_by FROM outbox_events WHERE event_id = :id").param("id", eventId)
			.query(String.class)
			.optional()).isEmpty();
		CentrifugoStubAssert.request(this.json, this.stub, eventId);
	}

	@Test
	void centrifugoDownKeepsEventPendingWithBackoff() {
		UUID eventId = appendEvent();
		this.stub.setDown(true);

		this.publisher.runOnce();

		OutboxRepository.StoredEvent stored = stored(eventId);
		assertThat(stored.status()).isEqualTo(OutboxStatus.PENDING);
		assertThat(stored.attempts()).isEqualTo(1);
		Duration wait = Duration.between(this.testClock.instant(), stored.nextAttemptAt().toInstant());
		assertThat(wait).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
		assertThat(stored.lastError()).isNotNull().hasSizeLessThan(100).doesNotContain("payload");
	}

	@Test
	void centrifugoErrorObjectIsAFailure() {
		UUID eventId = appendEvent();
		this.stub.respondForPath("/api/broadcast", 200, "{\"error\":{\"code\":102,\"message\":\"unknown channel\"}}");

		this.publisher.runOnce();

		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PENDING);
		assertThat(stored(eventId).attempts()).isEqualTo(1);
		assertThat(stored(eventId).lastError()).isEqualTo("102");
	}

	@Test
	void centrifugoRestartDrainsBacklog() {
		UUID eventId = appendEvent();
		this.stub.setDown(true);
		this.publisher.runOnce();
		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PENDING);

		this.stub.setDown(false);
		this.testClock.advance(Duration.ofSeconds(2));
		this.publisher.runOnce();

		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	void workerCrashAfterClaimIsRecoveredAfterLease() {
		UUID eventId = appendEvent();
		List<OutboxRepository.OutboxRow> claimed = this.repository.claimBatch("crashed-worker", 10,
				OffsetDateTime.now(this.testClock), this.properties.lease());
		assertThat(claimed).hasSize(1);

		this.publisher.runOnce();
		assertThat(this.stub.requestsFor("/api/broadcast")).isEmpty();
		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PENDING);

		this.testClock.advance(this.properties.lease().plusSeconds(1));
		this.publisher.runOnce();

		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	void workerCrashAfterPublishBeforeMarkRepublishes() {
		UUID eventId = appendEvent();
		this.hook.setAfterPublishBeforeMark(() -> {
			throw new IllegalStateException("worker crashed after publishing");
		});

		assertThatThrownBy(() -> this.publisher.runOnce()).isInstanceOf(IllegalStateException.class);
		this.hook.reset();

		assertThat(this.stub.requestsFor("/api/broadcast")).hasSize(1);
		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PENDING);
		assertThat(stored(eventId).attempts()).isEqualTo(1);

		this.testClock.advance(this.properties.lease().plusSeconds(1));
		this.publisher.runOnce();

		assertThat(this.stub.requestsFor("/api/broadcast")).hasSize(2);
		assertThat(this.stub.requestsFor("/api/broadcast")
			.stream()
			.map((request) -> this.json.readTree(request.body()).get("idempotency_key").asString())
			.distinct()
			.toList()).containsExactly(eventId.toString());
		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	void lostLeaseMarkIsIgnored() {
		UUID eventId = appendEvent();
		this.repository.claimBatch("worker-a", 10, OffsetDateTime.now(this.testClock), this.properties.lease());

		int updated = this.repository.markPublished(eventId, "worker-b", OffsetDateTime.now(this.testClock));

		assertThat(updated).isZero();
		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PENDING);
	}

	@Test
	void twoWorkersConcurrentlyClaimDisjointBatches() throws Exception {
		List<UUID> events = java.util.stream.IntStream.range(0, 200).mapToObj((index) -> appendEvent()).toList();

		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var workers = java.util.stream.IntStream.range(0, 2)
				.mapToObj((worker) -> executor.submit(() -> {
					for (int round = 0; round < 20 && pending() > 0; round++) {
						this.publisher.runOnce();
					}
					return null;
				}))
				.toList();
			for (var worker : workers) {
				worker.get(60, TimeUnit.SECONDS);
			}
		}

		assertThat(pending()).isZero();
		assertThat(this.repository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(200);
		Set<String> keys = new HashSet<>();
		for (var request : this.stub.requestsFor("/api/broadcast")) {
			keys.add(this.json.readTree(request.body()).get("idempotency_key").asString());
		}
		assertThat(keys).hasSize(200);
		assertThat(events).hasSize(200);
	}

	@Test
	void poisonEventIsParkedAfterMaxAttempts() {
		UUID eventId = appendEvent();
		this.stub.respondForPath("/api/broadcast", 200, "{\"error\":{\"code\":102,\"message\":\"unknown channel\"}}");

		for (int attempt = 0; attempt < this.properties.maxAttempts(); attempt++) {
			this.publisher.runOnce();
			this.testClock.advance(Duration.ofSeconds(61));
		}

		OutboxRepository.StoredEvent stored = stored(eventId);
		assertThat(stored.status()).isEqualTo(OutboxStatus.PARKED);
		assertThat(stored.attempts()).isEqualTo(this.properties.maxAttempts());
		assertThat(this.meters.get("outbox.parked.count").gauge().value()).isGreaterThanOrEqualTo(1);

		int attemptsWhenParked = stored.attempts();
		this.publisher.runOnce();
		assertThat(stored(eventId).attempts()).isEqualTo(attemptsWhenParked);
	}

	@Test
	void transportFailuresNeverPark() {
		UUID eventId = appendEvent();
		this.stub.setDown(true);

		for (int cycle = 0; cycle < 15; cycle++) {
			this.publisher.runOnce();
			this.testClock.advance(Duration.ofSeconds(61));
		}

		OutboxRepository.StoredEvent stored = stored(eventId);
		assertThat(stored.status()).isEqualTo(OutboxStatus.PENDING);
		assertThat(stored.attempts()).isGreaterThan(this.properties.maxAttempts());
		assertThat(this.repository.countByStatus(OutboxStatus.PARKED)).isZero();

		this.stub.setDown(false);
		this.stub.respond(200, "{\"result\":{}}");
		this.testClock.advance(Duration.ofSeconds(61));
		this.publisher.runOnce();

		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	void temporaryErrorNeverParks() {
		UUID eventId = appendEvent();
		this.stub.respondForPath("/api/broadcast", 200,
				"{\"error\":{\"code\":100,\"message\":\"internal\",\"temporary\":true}}");

		for (int cycle = 0; cycle < 12; cycle++) {
			this.publisher.runOnce();
			this.testClock.advance(Duration.ofSeconds(61));
		}

		OutboxRepository.StoredEvent stored = stored(eventId);
		assertThat(stored.status()).isEqualTo(OutboxStatus.PENDING);
		assertThat(stored.attempts()).isGreaterThan(this.properties.maxAttempts());
		assertThat(this.repository.countByStatus(OutboxStatus.PARKED)).isZero();

		this.stub.respondForPath("/api/broadcast", 200, "{\"result\":{}}");
		this.testClock.advance(Duration.ofSeconds(61));
		this.publisher.runOnce();
		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	void replayParkedEventResetsToPending() {
		UUID eventId = appendEvent();
		this.stub.respondForPath("/api/broadcast", 200, "{\"error\":{\"code\":102,\"message\":\"unknown channel\"}}");
		for (int attempt = 0; attempt < this.properties.maxAttempts(); attempt++) {
			this.publisher.runOnce();
			this.testClock.advance(Duration.ofSeconds(61));
		}
		assertThat(stored(eventId).status()).isEqualTo(OutboxStatus.PARKED);

		assertThat(this.repository.replay(eventId)).isEqualTo(1);

		OutboxRepository.StoredEvent replayed = stored(eventId);
		assertThat(replayed.status()).isEqualTo(OutboxStatus.PENDING);
		assertThat(replayed.attempts()).isZero();
	}

	@Test
	void largeBacklogIsProcessedInBoundedBatches() {
		this.jdbc.sql("""
				INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id, event_type,
				                           schema_version, channels, payload, next_attempt_at, created_at)
				SELECT uuidv7(), :tenant, 'conversation', 'bulk', 'message.created', 1,
				       ARRAY['chat:conversation:bulk'], '{}'::jsonb, now() - interval '1 minute',
				       now() - interval '1 minute'
				FROM generate_series(1, 10000)
				""").param("tenant", TENANT).update();
		assertThat(pending()).isEqualTo(10000);

		int processed = this.publisher.runOnce();

		assertThat(processed).isEqualTo(this.properties.batchSize());
		assertThat(pending()).isEqualTo(10000 - this.properties.batchSize());
		assertThat(this.meters.get("outbox.pending.oldest_age_seconds").gauge().value()).isGreaterThan(0.0);
	}

	@Test
	void metricsExposePendingAndParkedCounts() {
		appendEvent();
		appendEvent();
		assertThat(this.meters.get("outbox.pending.count").gauge().value()).isEqualTo(2.0);

		this.stub.respondForPath("/api/broadcast", 200, "{\"error\":{\"code\":102,\"message\":\"unknown channel\"}}");
		for (int attempt = 0; attempt < this.properties.maxAttempts(); attempt++) {
			this.publisher.runOnce();
			this.testClock.advance(Duration.ofSeconds(61));
		}

		assertThat(this.meters.get("outbox.parked.count").gauge().value()).isEqualTo(2.0);
		assertThat(this.meters.get("outbox.pending.count").gauge().value()).isZero();
	}

	private UUID appendEvent() {
		UUID aggregateId = UUID.randomUUID();
		UUID[] holder = new UUID[1];
		this.tx.executeWithoutResult((status) -> holder[0] = this.appender.append(TENANT, "conversation",
				aggregateId.toString(), "message.created",
				List.of("chat:conversation:" + aggregateId, "chat:inbox:" + TENANT + ":inbox"),
				java.util.Map.of("message", java.util.Map.of("id", "m-1"))));
		return holder[0];
	}

	private OutboxRepository.StoredEvent stored(UUID eventId) {
		return this.repository.findByEventId(eventId).orElseThrow();
	}

	private int pending() {
		return this.repository.countByStatus(OutboxStatus.PENDING);
	}

	/** Small assertion helper that keeps the Centrifugo expectations in one place. */
	static final class CentrifugoStubAssert {

		static void request(tools.jackson.databind.json.JsonMapper json, CentrifugoStub stub, UUID eventId) {
			JsonNode body = json.readTree(stub.requestsFor("/api/broadcast").getFirst().body());
			assertThat(body.get("channels").size()).isEqualTo(2);
			assertThat(body.get("idempotency_key").asString()).isEqualTo(eventId.toString());
			assertThat(body.get("data").get("eventType").asString()).isEqualTo("message.created");
		}

		private CentrifugoStubAssert() {
		}

	}

}
