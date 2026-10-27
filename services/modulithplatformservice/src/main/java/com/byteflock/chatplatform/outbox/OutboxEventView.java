package com.byteflock.chatplatform.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Admin view of one outbox row (Plan 03 adds the query and replay endpoints). */
public record OutboxEventView(UUID eventId, String tenantId, String eventType, OutboxStatus status, int attempts,
		OffsetDateTime nextAttemptAt, String lastError, OffsetDateTime createdAt, OffsetDateTime publishedAt) {
}
