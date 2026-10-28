package com.byteflock.chatplatform.conversation.internal;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.ContactAssignmentView;
import com.byteflock.chatplatform.conversation.ConversationStatus;

/**
 * The conversation object inside a realtime payload: the {@code ConversationView} without the per-caller
 * {@code unreadCount} and {@code agentOnline}, and with {@code assignment} as {@link ContactAssignmentView} (R14, R-P29).
 */
record EventConversation(UUID id, String tenantId, UUID inboxId, UUID contactId, String contactDisplayName,
		ConversationStatus status, long version, long lastSequence, OffsetDateTime lastMessageAt,
		String lastMessagePreview, ContactAssignmentView assignment, OffsetDateTime resolvedAt,
		OffsetDateTime archivedAt, OffsetDateTime purgedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

	static EventConversation of(com.byteflock.chatplatform.conversation.ConversationView view) {
		AssignmentState state = view.assignment().state();
		return new EventConversation(view.id(), view.tenantId(), view.inboxId(), view.contactId(),
				view.contactDisplayName(), view.status(), view.version(), view.lastSequence(), view.lastMessageAt(),
				view.lastMessagePreview(), new ContactAssignmentView(state, view.assignment().agentId()),
				view.resolvedAt(), view.archivedAt(), view.purgedAt(), view.createdAt(), view.updatedAt());
	}

}
