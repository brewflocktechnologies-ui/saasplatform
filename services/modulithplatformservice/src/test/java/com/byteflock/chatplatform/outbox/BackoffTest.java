package com.byteflock.chatplatform.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backoff schedule of the workers (Plan 01 rulings R-P15, R-P27): 0-based retry index, ±20 % jitter applied before the
 * cap, index clamped to [0, 30].
 */
class BackoffTest {

	static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

	static final Duration BASE = Duration.ofSeconds(1);

	static final Duration MAX = Duration.ofSeconds(60);

	/** Jitter factor exactly 1.0, so the schedule can be asserted before jitter. */
	static final RandomGenerator NEUTRAL = new RandomGenerator() {
		@Override
		public long nextLong() {
			return 0L;
		}

		@Override
		public double nextDouble() {
			return 0.5;
		}
	};

	@Test
	void growsExponentiallyAndCaps() {
		long[] expectedSeconds = { 1, 2, 4, 8, 16, 32, 60, 60, 60, 60, 60 };
		for (int n = 0; n < expectedSeconds.length; n++) {
			Duration delay = Duration.between(NOW,
					Backoff.next(NOW, n, BASE, MAX, NEUTRAL));
			assertThat(delay).as("retry index %s", n).isEqualTo(Duration.ofSeconds(expectedSeconds[n]));
		}
	}

	@Test
	void jitterStaysWithin20Percent() {
		RandomGenerator rng = new java.util.Random(42);
		for (int sample = 0; sample < 1000; sample++) {
			Duration delay = Duration.between(NOW, Backoff.next(NOW, 0, BASE, MAX, rng));
			assertThat(delay).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
		}
	}

	@Test
	void neverExceedsMax() {
		RandomGenerator rng = new java.util.Random(7);
		for (int sample = 0; sample < 1000; sample++) {
			assertThat(Duration.between(NOW, Backoff.next(NOW, 20, BASE, MAX, rng)))
				.isLessThanOrEqualTo(MAX);
		}
	}

	@Test
	void hugeRetryIndexCapsAtMax() {
		RandomGenerator rng = new java.util.Random(11);
		for (int n : new int[] { Integer.MAX_VALUE, 62, -1, 0 }) {
			for (int sample = 0; sample < 10; sample++) {
				Duration delay = Duration.between(NOW, Backoff.next(NOW, n, Duration.ofSeconds(2), MAX, rng));
				assertThat(delay).as("retry index %s", n).isLessThanOrEqualTo(MAX);
			}
		}
	}

}
