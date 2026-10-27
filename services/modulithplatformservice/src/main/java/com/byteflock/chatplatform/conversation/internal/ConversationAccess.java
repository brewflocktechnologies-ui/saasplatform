package com.byteflock.chatplatform.conversation.internal;

import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.ConversationAccessDeniedException;
import com.byteflock.chatplatform.conversation.ConversationNotFoundException;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.tenancy.Caller;
import org.springframework.stereotype.Component;

/**
 * Read and write authorization for one conversation: a contact may only reach its own conversation of its own inbox; an
 * agent may reach every conversation of its tenant (D14). A missing row is 404; an existing row outside the caller's
 * scope is 403, decided through the id-only probe (Plan 01 ruling R-P6).
 */
@Component
class ConversationAccess {

	private final ConversationRepository repository;

	ConversationAccess(ConversationRepository repository) {
		this.repository = repository;
	}

	ConversationRow lockForWrite(Caller caller, UUID conversationId) {
		return this.repository.lockForUpdate(caller.tenantId(), conversationId)
			.filter((row) -> visible(caller, row))
			.orElseThrow(() -> denied(caller, conversationId));
	}

	ConversationFacts lockForCursor(Caller caller, UUID conversationId) {
		return this.repository.lockForKeyShare(caller.tenantId(), conversationId)
			.filter((facts) -> visible(caller, facts))
			.orElseThrow(() -> denied(caller, conversationId));
	}

	/** Tenant-filtered read plus the visibility rule; used by history and detail reads. */
	ConversationRow requireReadable(Caller caller, UUID conversationId) {
		return this.repository.find(caller.tenantId(), conversationId)
			.filter((row) -> visible(caller, row))
			.orElseThrow(() -> denied(caller, conversationId));
	}

	ConversationView view(Caller caller, UUID conversationId) {
		return this.repository.findView(caller.tenantId(), conversationId, caller)
			.orElseThrow(() -> denied(caller, conversationId));
	}

	private boolean visible(Caller caller, ConversationRow row) {
		if (caller.isAgent()) {
			// The row was already read with the caller's tenant; every agent of the tenant may read every conversation.
			return true;
		}
		return caller.isContact() && row.contactId().equals(caller.contactId())
				&& row.inboxId().equals(caller.inboxId());
	}

	private boolean visible(Caller caller, ConversationFacts facts) {
		return !caller.isContact() || (facts.contactId().equals(caller.contactId())
				&& facts.inboxId().equals(caller.inboxId()));
	}

	private RuntimeException denied(Caller caller, UUID conversationId) {
		Optional<ConversationScope> scope = this.repository.tenantAndInboxOf(conversationId);
		if (scope.isEmpty()) {
			return new ConversationNotFoundException();
		}
		return new ConversationAccessDeniedException();
	}

}
