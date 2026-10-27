package com.byteflock.chatplatform.outbox.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Parameters of one outbox insert; always created by {@link com.byteflock.chatplatform.outbox.OutboxAppender}. */
public record OutboxRecord(UUID eventId, String tenantId, String aggregateType, String aggregateId, String eventType,
		int schemaVersion, List<String> channels, String payload, OffsetDateTime nextAttemptAt, String traceId) {
}
