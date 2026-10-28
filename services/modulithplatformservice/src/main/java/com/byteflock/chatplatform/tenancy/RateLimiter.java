package com.byteflock.chatplatform.tenancy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory token buckets for the session, message and typing limits (D17).
 * <p>
 * The {@code ponytail:} per-instance ceiling: a bucket lives in this JVM only, so two backend instances allow twice
 * the configured rate. Replace with a shared store before running more than one instance.
 * The time comes from the injected {@link Clock} so tests can drive the buckets with a {@code TestClock}.
 */
@Component
public class RateLimiter {

	private final Clock clock;

	private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	public RateLimiter(Clock clock) {
		this.clock = clock;
	}

	/** Refill-and-consume token bucket; {@code permitsPerMinute <= 0} disables the limit. */
	public boolean tryAcquire(String key, int permitsPerMinute) {
		if (permitsPerMinute <= 0) {
			return true;
		}
		Bucket bucket = this.buckets.computeIfAbsent(key, (ignored) -> new Bucket(permitsPerMinute));
		return bucket.tryConsume(this.clock.instant(), permitsPerMinute);
	}

	/** At most one permit per {@code minInterval} for the key; {@code minInterval} zero or negative disables it. */
	public boolean tryAcquireInterval(String key, Duration minInterval) {
		if (minInterval == null || minInterval.isZero() || minInterval.isNegative()) {
			return true;
		}
		Bucket bucket = this.buckets.computeIfAbsent(key, (ignored) -> new Bucket(1));
		return bucket.tryInterval(this.clock.instant(), minInterval);
	}

	/** Test hook: forget every bucket. */
	public void reset() {
		this.buckets.clear();
	}

	private static final class Bucket {

		private double tokens;

		private Instant lastRefill;

		private Instant lastInterval;

		private Bucket(double capacity) {
			this.tokens = capacity;
			this.lastRefill = null;
		}

		private synchronized boolean tryConsume(Instant now, int permitsPerMinute) {
			if (this.lastRefill == null) {
				this.lastRefill = now;
				this.tokens = permitsPerMinute;
			}
			long elapsedMillis = Math.max(0, Duration.between(this.lastRefill, now).toMillis());
			if (elapsedMillis > 0) {
				this.tokens = Math.min(permitsPerMinute,
						this.tokens + (elapsedMillis * permitsPerMinute) / 60_000.0);
				this.lastRefill = now;
			}
			if (this.tokens < 1.0) {
				return false;
			}
			this.tokens -= 1.0;
			return true;
		}

		private synchronized boolean tryInterval(Instant now, Duration minInterval) {
			if (this.lastInterval != null && now.isBefore(this.lastInterval.plus(minInterval))) {
				return false;
			}
			this.lastInterval = now;
			return true;
		}

	}

}
