package com.byteflock.chatplatform.tenancy.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Request and response bodies of the tenancy endpoints. Kept package-private: the HTTP contract is the OpenAPI document
 * in {@code libs/contracts/openapi/platform-api.yaml}, not these Java types.
 */
record WidgetSessionRequest(String widgetKey, String externalId, String displayName, String sessionToken) {
}

record WidgetSessionResponse(String sessionToken, Instant expiresAt, String tenantId, InboxView inbox,
		ContactView contact) {

	record InboxView(UUID id, String name) {
	}

	record ContactView(UUID id, String displayName) {
	}

}

record AgentLoginRequest(String agentId, String tenantId) {
}

record AgentSessionResponse(String sessionToken, Instant expiresAt, String tenantId, String agentId,
		String displayName) {
}
