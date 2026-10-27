package com.byteflock.chatplatform.conversation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.AssignmentView;
import com.byteflock.chatplatform.conversation.ContactAssignmentView;
import com.byteflock.chatplatform.conversation.ConversationStatus;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Conversation persistence. Every statement filters on {@code tenant_id} except the single id-only authorization probe
 * {@link #tenantAndInboxOf(UUID)} and statements keyed by primary key that run inside a transaction after a
 * tenant-filtered lock or lookup (Plan 01 ruling R-P6, amended).
 */
@Repository
public class ConversationRepository {

	private static final String COLUMNS = """
			c.id, c.tenant_id, c.inbox_id, c.contact_id, c.status, c.version, c.last_sequence, c.last_message_at,
			c.last_message_preview, c.assignment_state, c.assigned_agent_id, c.assignment_decision_id,
			c.assignment_reason, c.assignment_explanation, c.assignment_attempts, c.assignment_setup_failures,
			c.assignment_next_attempt_at, c.resolved_at, c.archived_at, c.archived_by, c.archive_reason, c.purged_at,
			c.created_at, c.updated_at
			""";

	private final JdbcClient jdbc;

	public ConversationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Locks the row for the write path; empty means it does not exist in this tenant. */
	public Optional<ConversationRow> lockForUpdate(String tenantId, UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM conversations c WHERE c.tenant_id = :tenantId AND c.id = :id"
				+ " FOR UPDATE")
			.param("tenantId", tenantId)
			.param("id", id)
			.query(ConversationRepository::row)
			.optional();
	}

	/** Tenant-filtered read without a lock. */
	public Optional<ConversationRow> find(String tenantId, UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM conversations c WHERE c.tenant_id = :tenantId AND c.id = :id")
			.param("tenantId", tenantId)
			.param("id", id)
			.query(ConversationRepository::row)
			.optional();
	}

	/**
	 * {@code FOR KEY SHARE} lock of the conversation row, taken before a read-cursor upsert so cursor writes and sends
	 * lock in the same order (conversation, then cursor) and never deadlock (Plan 01 ruling R-P26).
	 */
	public Optional<ConversationFacts> lockForKeyShare(String tenantId, UUID id) {
		return this.jdbc.sql("""
				SELECT c.last_sequence, c.tenant_id, c.inbox_id, c.contact_id FROM conversations c
				WHERE c.tenant_id = :tenantId AND c.id = :id FOR KEY SHARE
				""")
			.param("tenantId", tenantId)
			.param("id", id)
			.query((rs, row) -> new ConversationFacts(rs.getLong("last_sequence"), rs.getString("tenant_id"),
					rs.getObject("inbox_id", UUID.class), rs.getObject("contact_id", UUID.class)))
			.optional();
	}

	/**
	 * The one statement on a tenant-owned table without a tenant filter: it exists only to answer 403 (the id exists in
	 * another scope) versus 404, and never returns conversation data (Plan 01 ruling R-P6).
	 */
	public Optional<ConversationScope> tenantAndInboxOf(UUID id) {
		return this.jdbc.sql("SELECT tenant_id, inbox_id, contact_id FROM conversations WHERE id = :id")
			.param("id", id)
			.query((rs, row) -> new ConversationScope(rs.getString("tenant_id"),
					rs.getObject("inbox_id", UUID.class), rs.getObject("contact_id", UUID.class)))
			.optional();
	}

	/**
	 * Race-safe create: the partial unique index {@code ux_conversations_active_per_contact} makes the second
	 * concurrent insert a no-op instead of an error (D11).
	 */
	public Optional<UUID> insertActiveConversation(String tenantId, UUID inboxId, UUID contactId, OffsetDateTime now) {
		return this.jdbc.sql("""
				INSERT INTO conversations (tenant_id, inbox_id, contact_id, status, assignment_state,
				                           assignment_next_attempt_at)
				VALUES (:tenantId, :inboxId, :contactId, 'OPEN', 'REQUESTED', :now)
				ON CONFLICT (tenant_id, inbox_id, contact_id) WHERE status <> 'RESOLVED' AND archived_at IS NULL
				DO NOTHING
				RETURNING id
				""")
			.param("tenantId", tenantId)
			.param("inboxId", inboxId)
			.param("contactId", contactId)
			.param("now", now)
			.query(UUID.class)
			.optional();
	}

	public Optional<ConversationRow> findLatestNonArchived(String tenantId, UUID inboxId, UUID contactId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM conversations c
				WHERE c.tenant_id = :tenantId AND c.inbox_id = :inboxId AND c.contact_id = :contactId
				  AND c.archived_at IS NULL
				ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC
				LIMIT 1
				""")
			.param("tenantId", tenantId)
			.param("inboxId", inboxId)
			.param("contactId", contactId)
			.query(ConversationRepository::row)
			.optional();
	}

	/**
	 * The guarded state write: {@code version = version + 1} only in the same statement that the caller covers with one
	 * {@code conversation.updated} outbox row (R-P23). Zero rows means the row moved on — the caller answers
	 * {@code STALE_VERSION}.
	 */
	public int updateState(StateWrite write) {
		return this.jdbc.sql("""
				UPDATE conversations SET
				    status = :status,
				    version = version + 1,
				    updated_at = :now,
				    resolved_at = CASE WHEN :resolvedNow THEN :now ELSE resolved_at END,
				    archived_at = CASE WHEN :archiveWrite THEN (CASE WHEN :archived THEN :now ELSE NULL END)
				                       ELSE archived_at END,
				    archived_by = CASE WHEN :archiveWrite THEN (CASE WHEN :archived THEN :archivedBy ELSE NULL END)
				                       ELSE archived_by END,
				    archive_reason = CASE WHEN :archiveWrite THEN (CASE WHEN :archived THEN :archiveReason ELSE NULL END)
				                          ELSE archive_reason END,
				    assignment_state = :assignmentState,
				    assignment_next_attempt_at = CASE
				        WHEN :assignmentState IN ('REQUESTED', 'RELEASE_PENDING', 'REOPEN_PENDING')
				            THEN (CASE WHEN :fromFailed THEN :now
				                       ELSE GREATEST(COALESCE(assignment_next_attempt_at, :now), :now) END)
				        ELSE assignment_next_attempt_at END
				WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
				""")
			.param("status", write.status().name())
			.param("now", write.now())
			.param("resolvedNow", write.resolvedNow())
			.param("archiveWrite", write.archived() != null)
			.param("archived", Boolean.TRUE.equals(write.archived()))
			.param("archivedBy", write.archivedBy())
			.param("archiveReason", write.archiveReason())
			.param("assignmentState", write.assignmentState().name())
			.param("fromFailed", write.fromFailed())
			.param("id", write.id())
			.param("tenantId", write.tenantId())
			.param("expectedVersion", write.expectedVersion())
			.update();
	}

	/** {@code last_sequence + 1} under the row lock; messages only, never a version bump (D4, R-P9). */
	public long bumpSequence(UUID id) {
		return this.jdbc.sql("""
				UPDATE conversations SET last_sequence = last_sequence + 1 WHERE id = :id RETURNING last_sequence
				""").param("id", id).query(Long.class).single();
	}

	/** Message activity only: never changes {@code version} (R-P9). */
	public void touchActivity(UUID id, OffsetDateTime at, String preview) {
		this.jdbc.sql("""
				UPDATE conversations SET last_message_at = :at, last_message_preview = :preview WHERE id = :id
				""").param("at", at).param("preview", preview).param("id", id).update();
	}

	/** One conversation for one caller, with the caller's unread count (0 when purged, R-P31 M5). */
	public Optional<ConversationView> findView(String tenantId, UUID id, Caller caller) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				, ct.display_name AS contact_display_name, COALESCE(rc.last_read_sequence, 0) AS read_cursor
				FROM conversations c
				JOIN contacts ct ON ct.id = c.contact_id AND ct.tenant_id = c.tenant_id
				LEFT JOIN conversation_read_cursors rc ON rc.conversation_id = c.id AND rc.tenant_id = c.tenant_id
				     AND rc.participant_type = :participantType AND rc.participant_id = :participantId
				WHERE c.tenant_id = :tenantId AND c.id = :id
				""")
			.param("tenantId", tenantId)
			.param("id", id)
			.param("participantType", caller.type().name())
			.param("participantId", caller.principalId())
			.query((rs, row) -> view(rs, caller.type() == PrincipalType.AGENT))
			.optional();
	}

	/**
	 * Inbox page: {@code view} is {@code queue}, {@code mine} or {@code all}; {@code archived} selects the archive
	 * marker; the keyset cursor is {@code (activity, id)} descending. {@code limit} is the caller's page size + 1.
	 */
	public List<ConversationView> listForInbox(String tenantId, UUID inboxId, String view, boolean archived,
			InboxCursor cursor, int limit, Caller caller) {
		String pageClause = (cursor != null)
				? " AND (COALESCE(c.last_message_at, c.created_at), c.id)"
						+ "     < (CAST(:cursorAt AS timestamptz), CAST(:cursorId AS uuid))"
				: "";
		JdbcClient.StatementSpec statement = this.jdbc.sql("SELECT " + COLUMNS + """
				, ct.display_name AS contact_display_name, COALESCE(rc.last_read_sequence, 0) AS read_cursor
				FROM conversations c
				JOIN contacts ct ON ct.id = c.contact_id AND ct.tenant_id = c.tenant_id
				LEFT JOIN conversation_read_cursors rc ON rc.conversation_id = c.id AND rc.tenant_id = c.tenant_id
				     AND rc.participant_type = :participantType AND rc.participant_id = :participantId
				WHERE c.tenant_id = :tenantId AND c.inbox_id = :inboxId
				  AND (CASE WHEN :archived THEN c.archived_at IS NOT NULL ELSE c.archived_at IS NULL END)
				  AND (CAST(:view AS text) = 'all'
				       OR (CAST(:view AS text) = 'queue'
				           AND c.assignment_state IN ('REQUESTED', 'QUEUED', 'FAILED', 'REOPEN_PENDING'))
				       OR (CAST(:view AS text) = 'mine' AND c.assigned_agent_id = :agentId))
				""" + pageClause + """
				ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC
				LIMIT :limit
				""")
			.param("tenantId", tenantId)
			.param("inboxId", inboxId)
			.param("view", view)
			.param("archived", archived)
			.param("participantType", caller.type().name())
			.param("participantId", caller.principalId())
			.param("agentId", (caller.type() == PrincipalType.AGENT) ? caller.principalId() : "")
			.param("limit", limit);
		if (cursor != null) {
			statement = statement.param("cursorAt", cursor.activityAt()).param("cursorId", cursor.id());
		}
		return statement.query((rs, row) -> view(rs, caller.type() == PrincipalType.AGENT)).list();
	}

	record StateWrite(UUID id, String tenantId, long expectedVersion, ConversationStatus status,
			OffsetDateTime now, boolean resolvedNow, Boolean archived, String archivedBy, String archiveReason,
			AssignmentState assignmentState, boolean fromFailed) {
	}

	static ConversationRow row(ResultSet rs, int row) throws SQLException {
		return new ConversationRow(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
				rs.getObject("inbox_id", UUID.class), rs.getObject("contact_id", UUID.class),
				ConversationStatus.valueOf(rs.getString("status")), rs.getLong("version"), rs.getLong("last_sequence"),
				rs.getObject("last_message_at", OffsetDateTime.class), rs.getString("last_message_preview"),
				AssignmentState.valueOf(rs.getString("assignment_state")), rs.getString("assigned_agent_id"),
				rs.getObject("assignment_decision_id", UUID.class), rs.getString("assignment_reason"),
				rs.getString("assignment_explanation"), rs.getInt("assignment_attempts"),
				rs.getInt("assignment_setup_failures"),
				rs.getObject("assignment_next_attempt_at", OffsetDateTime.class),
				rs.getObject("resolved_at", OffsetDateTime.class), rs.getObject("archived_at", OffsetDateTime.class),
				rs.getString("archived_by"), rs.getString("archive_reason"),
				rs.getObject("purged_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("updated_at", OffsetDateTime.class));
	}

	static ConversationView view(ResultSet rs, boolean agentCaller) throws SQLException {
		ConversationRow row = row(rs, 0);
		long cursor = rs.getLong("read_cursor");
		long unread = row.purged() ? 0 : Math.max(0, row.lastSequence() - cursor);
		var assignment = agentCaller
				? new AssignmentView(row.assignmentState(), row.assignedAgentId(), row.assignmentDecisionId(),
						row.assignmentReason(), row.assignmentExplanation())
				: (com.byteflock.chatplatform.conversation.AssignmentInfo) new ContactAssignmentView(
						row.assignmentState(), row.assignedAgentId());
		return new ConversationView(row.id(), row.tenantId(), row.inboxId(), row.contactId(),
				rs.getString("contact_display_name"), row.status(), row.version(), row.lastSequence(),
				row.lastMessageAt(), row.lastMessagePreview(), assignment, row.resolvedAt(), row.archivedAt(),
				row.purgedAt(), row.createdAt(), row.updatedAt(), unread, null);
	}

}
