package com.byteflock.chatplatform.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A conversation as one caller may see it.
 *
 * @param purgedAt set when the retention purge removed the conversation's messages; {@code unreadCount} is then 0
 * @param agentOnline allocation presence of {@code assignment.agentId}; {@code null} when it cannot be determined
 * (computed from Plan 04 on)
 * @param unreadCount {@code lastSequence - cursor}, never negative, 0 when purged
 */
public record ConversationView(UUID id, String tenantId, UUID inboxId, UUID contactId, String contactDisplayName,
		ConversationStatus status, long version, long lastSequence, OffsetDateTime lastMessageAt,
		String lastMessagePreview, AssignmentInfo assignment, OffsetDateTime resolvedAt, OffsetDateTime archivedAt,
		OffsetDateTime purgedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt, long unreadCount,
		Boolean agentOnline) {
}
