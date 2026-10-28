package com.byteflock.chatplatform.outbox.internal;

import java.time.Duration;
import java.time.Instant;

import com.byteflock.chatplatform.outbox.OutboxStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * Outbox telemetry (master spec 15): backlog count, parked count, age of the oldest pending row, publish attempt
 * outcomes and publish duration. The gauges read the table on scrape, so they never depend on a worker tick.
 */
@Component
public class OutboxMetrics {

	private final OutboxRepository repository;

	private final MeterRegistry meters;

	OutboxMetrics(OutboxRepository repository, MeterRegistry meters) {
		this.repository = repository;
		this.meters = meters;
		Gauge.builder("outbox.pending.count", this,
				(metrics) -> metrics.repository.countByStatus(OutboxStatus.PENDING))
			.description("Outbox rows waiting to be published")
			.register(meters);
		Gauge.builder("outbox.parked.count", this,
				(metrics) -> metrics.repository.countByStatus(OutboxStatus.PARKED))
			.description("Outbox rows parked after a non-transport rejection")
			.register(meters);
		Gauge.builder("outbox.pending.oldest_age_seconds", this, OutboxMetrics::oldestPendingAgeSeconds)
			.description("Age of the oldest pending outbox row")
			.register(meters);
	}

	public void published(Duration duration) {
		attempt("success");
		Timer.builder("outbox.publish.duration").register(this.meters).record(duration);
	}

	public void failed(Duration duration) {
		attempt("failure");
		Timer.builder("outbox.publish.duration").register(this.meters).record(duration);
	}

	public void parked(Duration duration) {
		attempt("parked");
		Timer.builder("outbox.publish.duration").register(this.meters).record(duration);
	}

	private void attempt(String outcome) {
		this.meters.counter("outbox.publish.attempts", "outcome", outcome).increment();
	}

	private double oldestPendingAgeSeconds() {
		return this.repository.oldestPendingCreatedAt()
			.map((createdAt) -> (double) Math.max(0, Duration.between(createdAt, Instant.now()).toSeconds()))
			.orElse(0.0);
	}

}
