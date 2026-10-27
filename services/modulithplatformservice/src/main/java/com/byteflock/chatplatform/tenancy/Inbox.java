package com.byteflock.chatplatform.tenancy;

import java.util.UUID;

/** An inbox of a tenant; {@code widgetKey} is public (it identifies, it does not authorize). */
public record Inbox(UUID id, String tenantId, String name, String widgetKey, String allocationQueueId) {
}
