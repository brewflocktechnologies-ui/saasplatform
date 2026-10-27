package com.byteflock.chatplatform.tenancy.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.tenancy.Agent;
import com.byteflock.chatplatform.tenancy.Contact;
import com.byteflock.chatplatform.tenancy.Inbox;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link TenantDirectory}; every statement filters on {@code tenant_id}. */
@Repository
class JdbcTenantDirectory implements TenantDirectory {

	private final JdbcClient jdbc;

	JdbcTenantDirectory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Inbox> inboxByWidgetKey(String widgetKey) {
		return this.jdbc.sql("""
				SELECT id, tenant_id, name, widget_key, allocation_queue_id FROM inboxes WHERE widget_key = :widgetKey
				""").param("widgetKey", widgetKey).query(Inbox.class).optional();
	}

	@Override
	public Optional<Inbox> inbox(String tenantId, UUID inboxId) {
		return this.jdbc.sql("""
				SELECT id, tenant_id, name, widget_key, allocation_queue_id FROM inboxes
				WHERE tenant_id = :tenantId AND id = :inboxId
				""").param("tenantId", tenantId).param("inboxId", inboxId).query(Inbox.class).optional();
	}

	@Override
	public List<Inbox> inboxes(String tenantId) {
		return this.jdbc.sql("""
				SELECT id, tenant_id, name, widget_key, allocation_queue_id FROM inboxes
				WHERE tenant_id = :tenantId ORDER BY name, id
				""").param("tenantId", tenantId).query(Inbox.class).list();
	}

	@Override
	public Optional<Agent> agent(String tenantId, String agentId) {
		return this.jdbc.sql("""
				SELECT id, tenant_id, display_name FROM agents WHERE tenant_id = :tenantId AND id = :agentId
				""").param("tenantId", tenantId).param("agentId", agentId).query(Agent.class).optional();
	}

	@Override
	public List<Agent> agents(String tenantId) {
		return this.jdbc.sql("""
				SELECT id, tenant_id, display_name FROM agents WHERE tenant_id = :tenantId ORDER BY id
				""").param("tenantId", tenantId).query(Agent.class).list();
	}

	@Override
	public Contact ensureContact(String tenantId, String externalId, String displayName) {
		if (externalId == null) {
			return createAnonymousContact(tenantId);
		}
		return this.jdbc.sql("""
				INSERT INTO contacts (tenant_id, external_id, display_name) VALUES (:tenantId, :externalId, :displayName)
				ON CONFLICT (tenant_id, external_id) WHERE external_id IS NOT NULL
				DO UPDATE SET display_name = COALESCE(EXCLUDED.display_name, contacts.display_name)
				RETURNING id, tenant_id, external_id, display_name
				""")
			.param("tenantId", tenantId)
			.param("externalId", externalId)
			.param("displayName", displayName)
			.query(Contact.class)
			.single();
	}

	@Override
	public Contact createAnonymousContact(String tenantId) {
		return this.jdbc.sql("""
				INSERT INTO contacts (tenant_id) VALUES (:tenantId)
				RETURNING id, tenant_id, external_id, display_name
				""").param("tenantId", tenantId).query(Contact.class).single();
	}

	@Override
	public Optional<Contact> contact(String tenantId, UUID contactId) {
		return this.jdbc.sql("""
				SELECT id, tenant_id, external_id, display_name FROM contacts
				WHERE tenant_id = :tenantId AND id = :contactId
				""").param("tenantId", tenantId).param("contactId", contactId).query(Contact.class).optional();
	}

	@Override
	public void ensureTenant(String tenantId, String name) {
		this.jdbc.sql("""
				INSERT INTO tenants (id, name) VALUES (:tenantId, :name)
				ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name
				""").param("tenantId", tenantId).param("name", name).update();
	}

	@Override
	public Inbox ensureInbox(String tenantId, String name, String widgetKey, String allocationQueueId) {
		this.jdbc.sql("""
				INSERT INTO inboxes (tenant_id, name, widget_key, allocation_queue_id)
				VALUES (:tenantId, :name, :widgetKey, :queueId)
				ON CONFLICT (widget_key) DO NOTHING
				""")
			.param("tenantId", tenantId)
			.param("name", name)
			.param("widgetKey", widgetKey)
			.param("queueId", allocationQueueId)
			.update();
		return inboxByWidgetKey(widgetKey)
			.orElseThrow(() -> new IllegalStateException("inbox for widget key could not be read back"));
	}

	@Override
	public Agent ensureAgent(String tenantId, String agentId, String displayName) {
		return this.jdbc.sql("""
				INSERT INTO agents (tenant_id, id, display_name) VALUES (:tenantId, :agentId, :displayName)
				ON CONFLICT (tenant_id, id) DO UPDATE SET display_name = EXCLUDED.display_name
				RETURNING id, tenant_id, display_name
				""")
			.param("tenantId", tenantId)
			.param("agentId", agentId)
			.param("displayName", displayName)
			.query(Agent.class)
			.single();
	}

}
