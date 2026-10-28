package com.byteflock.chatplatform.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to tenants, inboxes, contacts and the agent roster. Implemented by {@code internal/JdbcTenantDirectory}.
 * <p>
 * The {@code ensure*} methods are idempotent upserts used by the local seeder, the admin tenants endpoint (Plan 05) and
 * integration-test fixtures; they never create a second row for the same natural key.
 */
public interface TenantDirectory {

	Optional<Inbox> inboxByWidgetKey(String widgetKey);

	Optional<Inbox> inbox(String tenantId, UUID inboxId);

	List<Inbox> inboxes(String tenantId);

	Optional<Agent> agent(String tenantId, String agentId);

	List<Agent> agents(String tenantId);

	Contact ensureContact(String tenantId, String externalId, String displayName);

	Contact createAnonymousContact(String tenantId);

	Optional<Contact> contact(String tenantId, UUID contactId);

	void ensureTenant(String tenantId, String name);

	/** Upserts by {@code widget_key} (globally unique), so a restart never changes the public widget key. */
	Inbox ensureInbox(String tenantId, String name, String widgetKey, String allocationQueueId);

	Agent ensureAgent(String tenantId, String agentId, String displayName);

}
