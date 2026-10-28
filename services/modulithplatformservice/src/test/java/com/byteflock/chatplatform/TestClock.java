package com.byteflock.chatplatform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Mutable clock for integration tests: the outbox backoff, leases and typing intervals are all driven from the injected
 * {@link Clock}, so tests move time explicitly instead of sleeping.
 */
public final class TestClock extends Clock {

	private volatile Instant now = Instant.now();

	public void reset() {
		this.now = Instant.now();
	}

	public void advance(Duration duration) {
		this.now = this.now.plus(duration);
	}

	public void set(Instant instant) {
		this.now = instant;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return this.now;
	}

}
