package com.byteflock.chatplatform.conversation.internal;

import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.tenancy.Inbox;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.byteflock.chatplatform.conversation.InboxPage;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;

/** Agent inbox: the tenant's inboxes and the paged conversation lists behind them. */
@RestController
@RequestMapping("/api/v1/agent/inboxes")
class AgentInboxController {

	private final TenantDirectory directory;

	private final ConversationService conversations;

	AgentInboxController(TenantDirectory directory, ConversationService conversations) {
		this.directory = directory;
		this.conversations = conversations;
	}

	@GetMapping
	@RequiresPrincipal(PrincipalType.AGENT)
	List<InboxView> inboxes(Caller caller) {
		return this.directory.inboxes(caller.tenantId()).stream().map(InboxView::of).toList();
	}

	@GetMapping("/{inboxId}/conversations")
	@RequiresPrincipal(PrincipalType.AGENT)
	InboxPage conversations(Caller caller, @PathVariable UUID inboxId,
			@RequestParam(name = "view", required = false, defaultValue = "all") String view,
			@RequestParam(name = "archived", required = false, defaultValue = "false") boolean archived,
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
		return this.conversations.listForAgent(caller, inboxId, view, archived, cursor, limit);
	}

	/** Public inbox metadata; the widget key identifies the inbox and is not a secret. */
	record InboxView(UUID id, String name, String widgetKey, String allocationQueueId) {

		static InboxView of(Inbox inbox) {
			return new InboxView(inbox.id(), inbox.name(), inbox.widgetKey(), inbox.allocationQueueId());
		}

	}

}
