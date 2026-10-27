package com.byteflock.chatplatform.conversation.internal;

import java.util.UUID;

import com.byteflock.chatplatform.conversation.MessagePage;
import com.byteflock.chatplatform.tenancy.Caller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * History pages (D13): {@code before} walks older messages in ascending pages, {@code after} is the catch-up path, no
 * cursor is the newest page, and {@code limit} is clamped to {@code app.limits.history-page-max}. Passing both cursors
 * is a validation error (Plan 01 ruling R-P11).
 */
@Service
public class HistoryService {

	private final ConversationAccess access;

	private final MessageRepository messages;

	private final int historyPageMax;

	HistoryService(ConversationAccess access, MessageRepository messages,
			@Value("${app.limits.history-page-max}") int historyPageMax) {
		this.access = access;
		this.messages = messages;
		this.historyPageMax = historyPageMax;
	}

	public MessagePage page(Caller caller, UUID conversationId, Long before, Long after, int limit) {
		if (before != null && after != null) {
			throw new IllegalArgumentException("before and after are mutually exclusive");
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		this.access.requireReadable(caller, conversationId);
		int pageSize = Math.min(limit, this.historyPageMax);
		return (after != null) ? this.messages.pageAfter(caller.tenantId(), conversationId, after, pageSize)
				: this.messages.pageBefore(caller.tenantId(), conversationId, before, pageSize);
	}

}
