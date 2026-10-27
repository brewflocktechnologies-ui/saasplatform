package com.byteflock.chatplatform.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * Retry schedule of the outbox (and the assignment worker): {@code now + min(base x 2^n ± 20 %, max)}.
 * <p>
 * {@code n} is a <strong>0-based retry index</strong>, so {@code n = 0} waits about {@code base}; callers pass
 * {@code attempts − 1} because the claim already counted the current attempt (Plan 01 ruling R-P15). The index is
 * clamped to [0, 30] before exponentiating: the assignment worker passes a monotonic attempt counter that passes 62
 * within minutes of {@code QUEUED} polling, and {@code 2^62 x base} overflows (Plan 01 ruling R-P27). Jitter is applied
 * before the cap, so a delay never exceeds {@code max}.
 */
public final class Backoff {

	private Backoff() {
	}

	public static Instant next(Instant now, int n, Duration base, Duration max, RandomGenerator rng) {
		int index = Math.min(Math.max(n, 0), 30);
		double exponentialMillis = base.toMillis() * Math.pow(2, index);
		double jittered = exponentialMillis * (0.8 + (rng.nextDouble() * 0.4));
		long delay = (long) Math.min(jittered, max.toMillis());
		return now.plusMillis(Math.max(delay, 0));
	}

}
