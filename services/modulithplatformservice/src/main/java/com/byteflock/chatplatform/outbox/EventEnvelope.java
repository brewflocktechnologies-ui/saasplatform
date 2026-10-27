package com.byteflock.chatplatform.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The realtime envelope stored in {@code outbox_events.payload} and published unchanged to Centrifugo (D12).
 *
 * @param eventId stable event id, identical on every redelivery
 * @param schemaVersion version of the event's JSON schema
 * @param conversationId conversation the event belongs to; {@code null} for conversation-less events
 * @param data event-specific payload
 */
public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, String tenantId,
		UUID conversationId, OffsetDateTime occurredAt, Object data) {
}
