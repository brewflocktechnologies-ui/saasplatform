package com.byteflock.chatplatform.tenancy;

import java.util.UUID;

/** A visitor identity of one tenant; {@code externalId} is {@code null} for anonymous contacts. */
public record Contact(UUID id, String tenantId, String externalId, String displayName) {
}
