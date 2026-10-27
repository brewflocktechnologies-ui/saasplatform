package com.byteflock.chatplatform.conversation;

import java.util.UUID;

/** Tenant and inbox parsed from an inbox channel ({@code chat:inbox:<tenantId>:<inboxId>}). */
public record InboxRef(String tenantId, UUID inboxId) {
}
