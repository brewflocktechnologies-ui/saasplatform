package com.byteflock.chatplatform.conversation.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Assignment worker settings ({@code app.assignment.*}, overview §5).
 *
 * @param enabled gates only the scheduled tick; the bean and {@code runOnce()} always exist
 * @param pollDelay next attempt after a transient failure or a {@code CONFLICT_RETRYABLE} outcome
 * @param queuedPoll fixed poll interval of a {@code QUEUED} row (the allocation sweep does the assigning)
 * @param failedPoll re-read interval of a {@code FAILED} row (never dispatched, only re-read, R-P17)
 * @param lease how long a worker claim stays valid
 * @param batchSize rows claimed per tick (R-P28)
 * @param maxBackoff ceiling of the unavailability backoff
 * @param maxSetupAttempts consecutive setup-code rejections before the row becomes {@code FAILED} (R-P2)
 */
@ConfigurationProperties("app.assignment")
public record AssignmentProperties(boolean enabled, Duration pollDelay, Duration queuedPoll, Duration failedPoll,
		Duration lease, int batchSize, Duration maxBackoff, int maxSetupAttempts) {
}
