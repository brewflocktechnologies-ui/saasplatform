package com.byteflock.chatplatform.outbox.internal;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import com.byteflock.chatplatform.outbox.Backoff;
import com.byteflock.chatplatform.realtime.BroadcastResult;
import com.byteflock.chatplatform.realtime.RealtimePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox publisher (D6, ADR-0007): claims a leased batch in one short transaction, publishes <em>outside</em> any
 * transaction through {@link RealtimePublisher#broadcast}, then marks each row in its own statement.
 * <p>
 * Rules: attempts are counted at claim time; a failed row retries at {@code Backoff.next(now, attempts − 1, …)}
 * (R-P15, R-P27); only a non-transport rejection of a row that already used {@code max-attempts} parks it, a transport
 * failure never parks (R-P30); before each broadcast the worker continues only if {@code now + 2 s ≤ locked_until}, else
 * the rest of the batch is left to be reclaimed after the lease (R-P28). The bean always exists and the scheduled tick
 * returns immediately when {@code app.outbox.enabled=false}; tests call {@link #runOnce()}.
 */
@Component
public class OutboxPublisher {

	/** The publish timeout the lease rule reserves before each Centrifugo call (R-P28). */
	static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private final OutboxRepository repository;

	private final RealtimePublisher publisher;

	private final OutboxProperties properties;

	private final OutboxHook hook;

	private final OutboxMetrics metrics;

	private final TransactionTemplate tx;

	private final JsonMapper json;

	private final Clock clock;

	private final RandomGenerator random = RandomGenerator.getDefault();

	private final String workerId;

	OutboxPublisher(OutboxRepository repository, RealtimePublisher publisher, OutboxProperties properties,
			OutboxHook hook, OutboxMetrics metrics, TransactionTemplate tx, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.publisher = publisher;
		this.properties = properties;
		this.hook = hook;
		this.metrics = metrics;
		this.tx = tx;
		this.json = json;
		this.clock = clock;
		this.workerId = workerId();
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-delay}")
	public void tick() {
		if (!this.properties.enabled()) {
			return;
		}
		try {
			runOnce();
		}
		catch (RuntimeException ex) {
			log.warn("Outbox tick failed: {}", ex.getClass().getSimpleName());
		}
	}

	/** Publishes at most one claimed batch; returns how many rows were processed. */
	public int runOnce() {
		Instant claimTime = this.clock.instant();
		List<OutboxRepository.OutboxRow> batch = this.tx.execute((status) -> this.repository
			.claimBatch(this.workerId, this.properties.batchSize(), OffsetDateTime.ofInstant(claimTime, java.time.ZoneOffset.UTC),
					this.properties.lease()));
		if (batch == null || batch.isEmpty()) {
			return 0;
		}
		int processed = 0;
		for (OutboxRepository.OutboxRow row : batch) {
			Instant now = this.clock.instant();
			// Per-row lease rule: do not start a publication that could outlive the claim (R-P28).
			if (row.lockedUntil() != null
					&& now.plus(PUBLISH_TIMEOUT).isAfter(row.lockedUntil().toInstant())) {
				log.debug("Outbox batch stopped before eventId={}: lease would expire", row.eventId());
				break;
			}
			JsonNode data = this.json.readTree(row.payload());
			Instant started = this.clock.instant();
			BroadcastResult result = this.publisher.broadcast(row.channels(), data, row.eventId().toString());
			this.hook.afterPublish();
			Duration elapsed = Duration.between(started, this.clock.instant());
			if (result.isPublished()) {
				this.repository.markPublished(row.eventId(), this.workerId, OffsetDateTime.now(this.clock));
				this.metrics.published(elapsed);
				log.debug("Outbox published eventId={} eventType={} attempts={} traceId={}", row.eventId(),
						row.eventType(), row.attempts(), row.traceId());
			}
			else {
				boolean parked = result.kind() == BroadcastResult.Kind.REJECTED
						&& row.attempts() >= this.properties.maxAttempts();
				Instant nextAttempt = parked ? this.clock.instant()
						: Backoff.next(this.clock.instant(), row.attempts() - 1, this.properties.baseBackoff(),
								this.properties.maxBackoff(), this.random);
				this.repository.markFailed(row.eventId(), this.workerId, OffsetDateTime.ofInstant(nextAttempt,
						java.time.ZoneOffset.UTC), result.error(), parked);
				if (parked) {
					this.metrics.parked(elapsed);
				}
				else {
					this.metrics.failed(elapsed);
				}
				log.warn("Outbox publish failed eventId={} eventType={} attempts={} traceId={} kind={} error={}", row.eventId(),
						row.eventType(), row.attempts(), row.traceId(), result.kind(), result.error());
			}
			processed++;
		}
		return processed;
	}

	private static String workerId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			host = "worker";
		}
		return host + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
