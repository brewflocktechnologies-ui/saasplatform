package com.byteflock.chatplatform.tenancy;

import java.util.UUID;

/**
 * Verified principal of a request.
 *
 * @param tenantId tenant the principal belongs to; {@code null} only for the local-dev admin
 * @param type kind of principal
 * @param principalId contact id, agent id or {@code "admin"}
 * @param inboxId inbox of a contact, {@code null} for agents and admins
 */
public record Caller(String tenantId, PrincipalType type, String principalId, UUID inboxId) {

	public static Caller admin() {
		return new Caller(null, PrincipalType.ADMIN, "admin", null);
	}

	public boolean isContact() {
		return this.type == PrincipalType.CONTACT;
	}

	public boolean isAgent() {
		return this.type == PrincipalType.AGENT;
	}

	public UUID contactId() {
		return UUID.fromString(this.principalId);
	}

}
