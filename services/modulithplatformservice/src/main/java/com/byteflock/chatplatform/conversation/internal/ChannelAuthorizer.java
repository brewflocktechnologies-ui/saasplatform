package com.byteflock.chatplatform.conversation.internal;

import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.Channels;
import com.byteflock.chatplatform.conversation.ConversationAccessDeniedException;
import com.byteflock.chatplatform.conversation.ConversationNotFoundException;
import com.byteflock.chatplatform.conversation.InboxRef;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.stereotype.Component;

/**
 * Channel authorization for subscription tokens (D14, tenant-security.md §5). A token is only ever issued for a channel
 * the caller may read, and it is bound to that exact channel.
 * <p>
 * Conversation channels: a contact needs its own conversation of its own inbox; an agent needs a conversation of its
 * tenant. Inbox channels need no probe because the channel carries the tenant (Plan 01 ruling R-P18): a different tenant
 * is 403 from the name alone, and a same-tenant channel whose inbox does not exist (an unknown id, or another tenant's
 * inbox id) is 404 so the other tenant's inbox is never confirmed (R-P22). A contact may never subscribe to an inbox
 * channel.
 */
@Component
class ChannelAuthorizer {

	private final TenantDirectory directory;

	private final ConversationRepository conversations;

	ChannelAuthorizer(TenantDirectory directory, ConversationRepository conversations) {
		this.directory = directory;
		this.conversations = conversations;
	}

	void authorize(Caller caller, String channel) {
		if (channel == null || channel.isBlank()) {
			throw new IllegalArgumentException("channel is required");
		}
		Optional<UUID> conversationId = Channels.conversationId(channel);
		if (conversationId.isPresent()) {
			authorizeConversation(caller, conversationId.get());
			return;
		}
		Optional<InboxRef> inbox = Channels.inboxRef(channel);
		if (inbox.isPresent()) {
			authorizeInbox(caller, inbox.get());
			return;
		}
		throw new IllegalArgumentException("channel is malformed");
	}

	private void authorizeConversation(Caller caller, UUID conversationId) {
		boolean visible = this.conversations.findView(caller.tenantId(), conversationId, caller)
			.filter((view) -> !caller.isContact()
					|| (view.contactId().equals(caller.contactId()) && view.inboxId().equals(caller.inboxId())))
			.isPresent();
		if (visible) {
			return;
		}
		// The id-only probe decides 404 (no such conversation) versus 403 (exists in another scope) — R-P6.
		if (this.conversations.tenantAndInboxOf(conversationId).isEmpty()) {
			throw new ConversationNotFoundException();
		}
		throw new ConversationAccessDeniedException();
	}

	private void authorizeInbox(Caller caller, InboxRef inbox) {
		if (!caller.isAgent()) {
			throw new ConversationAccessDeniedException();
		}
		if (!inbox.tenantId().equals(caller.tenantId())) {
			throw new ConversationAccessDeniedException();
		}
		this.directory.inbox(caller.tenantId(), inbox.inboxId())
			.orElseThrow(ConversationNotFoundException::new);
	}

}
