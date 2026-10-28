package com.byteflock.chatplatform.conversation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves the V3 schema itself: the migration is applied, the tenant-scoped constraints hold and the nullability matches
 * {@code docs/architecture/domain-model.md} section 3 exactly (Plan 01 rulings R-P7, R-P2).
 */
class ConversationSchemaIT extends PlatformIntegrationTest {

	private static final String TENANT = "tenant-schema";

	@Test
	void migratesFromEmptyDatabaseToV3() {
		String version = this.jdbc.sql("SELECT version FROM platform.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
			.query(String.class)
			.single();
		assertThat(version).isEqualTo("3");
	}

	@Test
	void activeConversationPerContactIsUnique() {
		UUID inbox = seedInbox("wk-schema");
		UUID contact = seedContact(null);

		UUID first = insertConversation(inbox, contact, "OPEN");
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertConversation(inbox, contact, "OPEN"));

		this.jdbc.sql("UPDATE conversations SET status = 'RESOLVED', resolved_at = clock_timestamp() WHERE id = :id")
			.param("id", first)
			.update();
		UUID second = insertConversation(inbox, contact, "OPEN");
		assertThat(second).isNotEqualTo(first);
		assertThat(this.jdbc.sql("SELECT count(*) FROM conversations WHERE contact_id = :id")
			.param("id", contact)
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void messageConstraints() {
		UUID conversation = insertConversation(seedInbox("wk-messages"), seedContact(null), "OPEN");

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertMessage(conversation, 1, "CONTACT", "sender-a", "   ", null));
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertMessage(conversation, 1, "CONTACT", "sender-a", "x".repeat(4001), null));
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertMessage(conversation, 1, "CONTACT", "sender-a", "x".repeat(4000) + "x", null));

		insertMessage(conversation, 1, "CONTACT", "sender-a", "hello", "c-1");
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertMessage(conversation, 1, "CONTACT", "sender-a", "duplicate sequence", "c-2"));
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertMessage(conversation, 2, "CONTACT", "sender-a", "duplicate key", "c-1"));
		// The same clientMessageId is fine for a different sender of the same conversation (D5).
		insertMessage(conversation, 2, "AGENT", "sender-b", "same key, other sender", "c-1");
	}

	@Test
	void outboxDefaults() {
		Map<String, Object> row = this.jdbc.sql("""
				INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id, event_type, channels, payload)
				VALUES (uuidv7(), :tenant, 'conversation', 'aggregate', 'message.created',
				        ARRAY['chat:conversation:aggregate'], '{}'::jsonb)
				RETURNING status, attempts, next_attempt_at
				""")
			.param("tenant", TENANT)
			.query((rs, n) -> {
				Map<String, Object> values = new LinkedHashMap<>();
				values.put("status", rs.getString("status"));
				values.put("attempts", rs.getInt("attempts"));
				values.put("nextAttemptAt", rs.getObject("next_attempt_at"));
				return values;
			})
			.single();
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("attempts")).isEqualTo(0);
		assertThat(row.get("nextAttemptAt")).isNotNull();
	}

	@Test
	void nullabilityMatchesDomainModel() {
		Map<String, Set<String>> expected = new LinkedHashMap<>();
		expected.put("tenants", Set.of());
		expected.put("inboxes", Set.of());
		expected.put("contacts", Set.of("external_id", "display_name"));
		expected.put("agents", Set.of());
		expected.put("conversations", Set.of("last_message_at", "last_message_preview", "assigned_agent_id",
				"assignment_decision_id", "assignment_reason", "assignment_explanation", "assignment_next_attempt_at",
				"resolved_at", "archived_at", "archived_by", "archive_reason", "purged_at"));
		expected.put("conversation_participants", Set.of());
		expected.put("messages", Set.of("client_message_id", "request_fingerprint", "reply_to_message_id", "metadata",
				"edited_at", "deleted_at", "deleted_by", "deletion_reason"));
		expected.put("message_attachments", Set.of());
		expected.put("conversation_read_cursors", Set.of());
		expected.put("conversation_state_events", Set.of("details"));
		expected.put("outbox_events", Set.of("locked_by", "locked_until", "last_error", "trace_id", "published_at"));
		expected.put("retention_policies", Set.of());
		expected.put("purge_runs", Set.of("last_error", "finished_at"));

		Map<String, Set<String>> actual = new LinkedHashMap<>();
		List<Map<String, String>> rows = this.jdbc.sql("""
				SELECT table_name, column_name FROM information_schema.columns
				WHERE table_schema = 'platform' AND is_nullable = 'YES'
				ORDER BY table_name, ordinal_position
				""").query((rs, n) -> Map.of("table", rs.getString("table_name"), "column", rs.getString("column_name")))
			.list();
		rows.forEach((row) -> {
			if (expected.containsKey(row.get("table"))) {
				actual.computeIfAbsent(row.get("table"), (key) -> new LinkedHashSet<>()).add(row.get("column"));
			}
		});
		expected.keySet().forEach((table) -> actual.putIfAbsent(table, Set.of()));

		for (Map.Entry<String, Set<String>> table : expected.entrySet()) {
			assertThat(actual.get(table.getKey())).as("nullable columns of %s", table.getKey())
				.containsExactlyInAnyOrderElementsOf(table.getValue());
		}
		assertThat(actual.keySet()).as("V3 tables").isEqualTo(expected.keySet());

		Map<String, String> column = this.jdbc.sql("""
				SELECT is_nullable, column_default FROM information_schema.columns
				WHERE table_schema = 'platform' AND table_name = 'conversations'
				  AND column_name = 'assignment_setup_failures'
				""").query((rs, n) -> Map.of("nullable", rs.getString("is_nullable"), "default", rs.getString("column_default")))
			.single();
		assertThat(column.get("nullable")).isEqualTo("NO");
		assertThat(column.get("default")).isEqualTo("0");
	}

	private UUID seedInbox(String widgetKey) {
		if (this.jdbc.sql("SELECT count(*) FROM tenants WHERE id = :id").param("id", TENANT).query(Integer.class).single() == 0) {
			insertTenant(TENANT);
		}
		return this.jdbc.sql("""
				INSERT INTO inboxes (tenant_id, name, widget_key, allocation_queue_id)
				VALUES (:tenant, 'Website', :widgetKey, 'support') RETURNING id
				""")
			.param("tenant", TENANT)
			.param("widgetKey", widgetKey)
			.query(UUID.class)
			.single();
	}

	private UUID seedContact(String externalId) {
		return this.jdbc.sql("""
				INSERT INTO contacts (tenant_id, external_id, display_name)
				VALUES (:tenant, :externalId, 'Visitor') RETURNING id
				""")
			.param("tenant", TENANT)
			.param("externalId", externalId)
			.query(UUID.class)
			.single();
	}

	private UUID insertConversation(UUID inbox, UUID contact, String status) {
		return this.jdbc.sql("""
				INSERT INTO conversations (tenant_id, inbox_id, contact_id, status)
				VALUES (:tenant, :inbox, :contact, :status) RETURNING id
				""")
			.param("tenant", TENANT)
			.param("inbox", inbox)
			.param("contact", contact)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

	private void insertMessage(UUID conversation, long sequence, String senderType, String senderId, String body,
			String clientMessageId) {
		this.jdbc.sql("""
				INSERT INTO messages (tenant_id, conversation_id, sequence, sender_type, sender_id, body, client_message_id)
				VALUES (:tenant, :conversation, :sequence, :senderType, :senderId, :body, :clientMessageId)
				""")
			.param("tenant", TENANT)
			.param("conversation", conversation)
			.param("sequence", sequence)
			.param("senderType", senderType)
			.param("senderId", senderId)
			.param("body", body)
			.param("clientMessageId", clientMessageId)
			.update();
	}

}
