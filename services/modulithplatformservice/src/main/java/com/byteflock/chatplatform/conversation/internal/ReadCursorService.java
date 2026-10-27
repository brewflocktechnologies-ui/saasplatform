package com.byteflock.chatplatform.conversation.internal;

import java.util.UUID;

import com.byteflock.chatplatform.tenancy.Caller;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read cursors (D13, R12): the cursor is clamped to the conversation's {@code last_sequence}, monotonic
 * ({@code GREATEST}) and written only after a tenant-filtered {@code FOR KEY SHARE} lock of the conversation row, the
 * same lock order a send uses (Plan 01 ruling R-P26).
 */
@Service
public class ReadCursorService {

	private final TransactionTemplate tx;

	private final ConversationAccess access;

	private final ReadCursorRepository cursors;

	ReadCursorService(TransactionTemplate tx, ConversationAccess access, ReadCursorRepository cursors) {
		this.tx = tx;
		this.access = access;
		this.cursors = cursors;
	}

	public void advance(Caller caller, UUID conversationId, long lastReadSequence) {
		if (lastReadSequence < 0) {
			throw new IllegalArgumentException("lastReadSequence must not be negative");
		}
		this.tx.executeWithoutResult((status) -> {
			ConversationFacts facts = this.access.lockForCursor(caller, conversationId);
			long target = Math.min(lastReadSequence, facts.lastSequence());
			this.cursors.advance(conversationId, caller.tenantId(), caller.type().name(), caller.principalId(), target);
		});
	}

}
