package com.byteflock.chatplatform.tenancy;

/** Roster agent of one tenant; the id equals the allocation agent id. The same id may exist in two tenants. */
public record Agent(String id, String tenantId, String displayName) {
}
