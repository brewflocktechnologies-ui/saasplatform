package com.byteflock.chatplatform.conversation.internal;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.ConversationStatus;

/** One conversation row as read under the row lock; the mutable state the services reason about. */
record ConversationRow(UUID id, String tenantId, UUID inboxId, UUID contactId, ConversationStatus status, long version,
		long lastSequence, OffsetDateTime lastMessageAt, String lastMessagePreview, AssignmentState assignmentState,
		String assignedAgentId, UUID assignmentDecisionId, String assignmentReason, String assignmentExplanation,
		int assignmentAttempts, int assignmentSetupFailures, OffsetDateTime assignmentNextAttemptAt,
		OffsetDateTime resolvedAt, OffsetDateTime archivedAt, String archivedBy, String archiveReason,
		OffsetDateTime purgedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

	boolean archived() {
		return this.archivedAt != null;
	}

	boolean purged() {
		return this.purgedAt != null;
	}

}

/** The id-only authorization probe: scope of a conversation, never its data (Plan 01 ruling R-P6). */
record ConversationScope(String tenantId, UUID inboxId, UUID contactId) {
}

/** Facts read under a {@code FOR KEY SHARE} lock; used by the read-cursor path (R-P26). */
record ConversationFacts(long lastSequence, String tenantId, UUID inboxId, UUID contactId) {
}

/** Keyset cursor of an inbox page: activity time of the last row plus its id, both descending. */
record InboxCursor(OffsetDateTime activityAt, UUID id) {
}
