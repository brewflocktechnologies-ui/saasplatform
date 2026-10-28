package com.byteflock.chatplatform.conversation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.tenancy.Agent;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.Contact;
import com.byteflock.chatplatform.tenancy.Inbox;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fixtures for the conversation integration tests. Every fixture gets a fresh random tenant, so in-memory state keyed by
 * tenant (rate-limit buckets, caches) never leaks between tests, and every assertion about counts is tenant-scoped.
 */
public final class ConversationTestData {

	/** One tenant with its inbox, contact and agent. */
	public record Fixture(String tenantId, Inbox inbox, Contact contact, Agent agent) {

		public Caller contactCaller() {
			return new Caller(this.tenantId, PrincipalType.CONTACT, this.contact.id().toString(), this.inbox.id());
		}

		public Caller agentCaller() {
			return new Caller(this.tenantId, PrincipalType.AGENT, this.agent.id(), null);
		}

	}

	private final TenantDirectory directory;

	private final JdbcClient jdbc;

	public ConversationTestData(TenantDirectory directory, JdbcClient jdbc) {
		this.directory = directory;
		this.jdbc = jdbc;
	}

	public Fixture seed() {
		return seed("fixture-" + UUID.randomUUID().toString().substring(0, 8));
	}

	public Fixture seed(String suffix) {
		String tenantId = "tenant-" + suffix;
		this.directory.ensureTenant(tenantId, "Tenant " + suffix);
		Inbox inbox = this.directory.ensureInbox(tenantId, "Website", "wk-" + suffix, "support");
		Contact contact = this.directory.ensureContact(tenantId, "visitor-" + suffix, "Visitor " + suffix);
		Agent agent = this.directory.ensureAgent(tenantId, "agentbob", "Agent Bob");
		return new Fixture(tenantId, inbox, contact, agent);
	}

	public UUID start(ConversationService service, Fixture fixture) {
		return service.startOrResume(fixture.contactCaller()).conversation().id();
	}

	public UUID start(ConversationService service, Fixture fixture, Caller secondContact) {
		return service.startOrResume(secondContact).conversation().id();
	}

	public void setAssignment(UUID conversationId, String state, String agentId, OffsetDateTime nextAttemptAt) {
		this.jdbc.sql("""
				UPDATE conversations SET assignment_state = :state, assigned_agent_id = :agentId,
				       assignment_next_attempt_at = :nextAttemptAt, assignment_decision_id = :decisionId,
				       assignment_reason = 'TEST_REASON', assignment_explanation = 'test explanation'
				WHERE id = :id
				""")
			.param("state", state)
			.param("agentId", agentId)
			.param("nextAttemptAt", nextAttemptAt)
			.param("decisionId", agentId != null ? UUID.randomUUID() : null)
			.param("id", conversationId)
			.update();
	}

	public void archive(UUID conversationId, String archivedBy) {
		this.jdbc.sql("""
				UPDATE conversations SET archived_at = clock_timestamp(), archived_by = :by WHERE id = :id
				""").param("by", archivedBy).param("id", conversationId).update();
	}

	public void purge(UUID conversationId) {
		this.jdbc.sql("UPDATE conversations SET purged_at = clock_timestamp() WHERE id = :id")
			.param("id", conversationId)
			.update();
	}

	public String status(UUID conversationId) {
		return this.jdbc.sql("SELECT status FROM conversations WHERE id = :id")
			.param("id", conversationId)
			.query(String.class)
			.single();
	}

	public String assignmentState(UUID conversationId) {
		return this.jdbc.sql("SELECT assignment_state FROM conversations WHERE id = :id")
			.param("id", conversationId)
			.query(String.class)
			.single();
	}

	public long version(UUID conversationId) {
		return this.jdbc.sql("SELECT version FROM conversations WHERE id = :id")
			.param("id", conversationId)
			.query(Long.class)
			.single();
	}

	public long lastSequence(UUID conversationId) {
		return this.jdbc.sql("SELECT last_sequence FROM conversations WHERE id = :id")
			.param("id", conversationId)
			.query(Long.class)
			.single();
	}

	public OffsetDateTime assignmentNextAttemptAt(UUID conversationId) {
		return this.jdbc.sql("SELECT assignment_next_attempt_at FROM conversations WHERE id = :id")
			.param("id", conversationId)
			.query(OffsetDateTime.class)
			.single();
	}

	public OffsetDateTime resolvedAt(UUID conversationId) {
		return this.jdbc.sql("SELECT resolved_at FROM conversations WHERE id = :id")
			.param("id", conversationId)
			.query(OffsetDateTime.class)
			.optional()
			.orElse(null);
	}

	public int messageCount(UUID conversationId) {
		return this.jdbc.sql("SELECT count(*) FROM messages WHERE conversation_id = :id")
			.param("id", conversationId)
			.query(Integer.class)
			.single();
	}

	public int stateEventCount(UUID conversationId, String eventType) {
		return this.jdbc.sql("SELECT count(*) FROM conversation_state_events"
				+ " WHERE conversation_id = :id AND event_type = :eventType")
			.param("id", conversationId)
			.param("eventType", eventType)
			.query(Integer.class)
			.single();
	}

	public int outboxCount(UUID conversationId, String eventType) {
		return this.jdbc.sql("SELECT count(*) FROM outbox_events"
				+ " WHERE aggregate_type = 'conversation' AND aggregate_id = :id AND event_type = :eventType")
			.param("id", conversationId.toString())
			.param("eventType", eventType)
			.query(Integer.class)
			.single();
	}

	public List<String> outboxPayloads(UUID conversationId, String eventType) {
		return this.jdbc.sql("SELECT payload FROM outbox_events"
				+ " WHERE aggregate_type = 'conversation' AND aggregate_id = :id AND event_type = :eventType"
				+ " ORDER BY created_at")
			.param("id", conversationId.toString())
			.param("eventType", eventType)
			.query(String.class)
			.list();
	}

	public String outboxChannels(UUID conversationId, String eventType) {
		return this.jdbc.sql("SELECT array_to_string(channels, ',') FROM outbox_events"
				+ " WHERE aggregate_id = :id AND event_type = :eventType ORDER BY created_at LIMIT 1")
			.param("id", conversationId.toString())
			.param("eventType", eventType)
			.query(String.class)
			.single();
	}

	public int participantCount(UUID conversationId, String participantType, String participantId) {
		return this.jdbc.sql("SELECT count(*) FROM conversation_participants"
				+ " WHERE conversation_id = :id AND participant_type = :type AND participant_id = :participantId")
			.param("id", conversationId)
			.param("type", participantType)
			.param("participantId", participantId)
			.query(Integer.class)
			.single();
	}

	public long cursor(UUID conversationId, String participantType, String participantId) {
		return this.jdbc.sql("SELECT last_read_sequence FROM conversation_read_cursors"
				+ " WHERE conversation_id = :id AND participant_type = :type AND participant_id = :participantId")
			.param("id", conversationId)
			.param("type", participantType)
			.param("participantId", participantId)
			.query(Long.class)
			.single();
	}

}
