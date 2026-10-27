package com.byteflock.chatplatform.outbox.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox worker settings ({@code app.outbox.*}, overview §5).
 *
 * @param enabled whether the scheduled tick publishes; the bean and {@code runOnce()} always exist
 * @param pollDelay fixed delay of the scheduled tick
 * @param batchSize rows claimed per tick
 * @param lease how long a claim stays valid for the claiming worker
 * @param maxAttempts attempts after which a non-transport failure parks the row (never a transport failure)
 * @param baseBackoff first retry delay ({@code n = 0})
 * @param maxBackoff ceiling of the exponential schedule
 */
@ConfigurationProperties("app.outbox")
public record OutboxProperties(boolean enabled, Duration pollDelay, int batchSize, Duration lease, int maxAttempts,
		Duration baseBackoff, Duration maxBackoff) {
}
