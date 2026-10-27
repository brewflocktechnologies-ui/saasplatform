package com.byteflock.chatplatform.retention.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.retention.PurgeRunView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Retention persistence. Every statement filters on {@code tenant_id}; the purge deletes only conversation-owned rows and
 * keeps the conversation and its state events (D16, R-P25).
 */
@Repository
public class RetentionRepository {

	private final JdbcClient jdbc;

	public RetentionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Integer> retentionDays(String tenantId) {
		return this.jdbc.sql("SELECT resolved_retention_days FROM retention_policies WHERE tenant_id = :tenantId")
			.param("tenantId", tenantId)
			.query(Integer.class)
			.optional();
	}

	public boolean legalHold(String tenantId) {
		return this.jdbc.sql("SELECT COALESCE(bool_or(legal_hold), false) FROM retention_policies WHERE tenant_id = :tenantId")
			.param("tenantId", tenantId)
			.query(Boolean.class)
			.single();
	}

	/** Eligible: not purged and (resolved before the cutoff, or archived before the cutoff) — R-P25. */
	public List<UUID> eligible(String tenantId, OffsetDateTime cutoff, int limit) {
		return this.jdbc.sql("""
				SELECT id FROM conversations
				WHERE tenant_id = :tenantId AND purged_at IS NULL
				  AND ((status = 'RESOLVED' AND resolved_at < :cutoff) OR (archived_at IS NOT NULL AND archived_at < :cutoff))
				ORDER BY id
				LIMIT :limit
				""")
			.param("tenantId", tenantId)
			.param("cutoff", cutoff)
			.param("limit", limit)
			.query(UUID.class)
			.list();
	}

	public int countMessages(UUID conversationId) {
		return this.jdbc.sql("SELECT count(*) FROM messages WHERE conversation_id = :id")
			.param("id", conversationId)
			.query(Integer.class)
			.single();
	}

	public int countAttachments(UUID conversationId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM message_attachments a JOIN messages m ON m.id = a.message_id
				WHERE m.conversation_id = :id
				""").param("id", conversationId).query(Integer.class).single();
	}

	public int countCursors(UUID conversationId) {
		return this.jdbc.sql("SELECT count(*) FROM conversation_read_cursors WHERE conversation_id = :id")
			.param("id", conversationId)
			.query(Integer.class)
			.single();
	}

	public int countOutbox(UUID conversationId) {
		return this.jdbc.sql("""
				SELECT count(*) FROM outbox_events WHERE aggregate_type = 'conversation' AND aggregate_id = :id
				""").param("id", conversationId.toString()).query(Integer.class).single();
	}

	/** Deletes the conversation-owned rows and marks the conversation purged, archived and resolved (D16). */
	public void purgeConversation(String tenantId, UUID conversationId, OffsetDateTime now) {
		this.jdbc.sql("""
				DELETE FROM message_attachments WHERE message_id IN
				    (SELECT id FROM messages WHERE conversation_id = :id AND tenant_id = :tenantId)
				""").param("id", conversationId).param("tenantId", tenantId).update();
		this.jdbc.sql("DELETE FROM messages WHERE conversation_id = :id AND tenant_id = :tenantId")
			.param("id", conversationId)
			.param("tenantId", tenantId)
			.update();
		this.jdbc.sql("DELETE FROM conversation_read_cursors WHERE conversation_id = :id AND tenant_id = :tenantId")
			.param("id", conversationId)
			.param("tenantId", tenantId)
			.update();
		this.jdbc.sql("""
				DELETE FROM outbox_events WHERE tenant_id = :tenantId AND aggregate_type = 'conversation'
				  AND aggregate_id = :aggregateId
				""").param("tenantId", tenantId).param("aggregateId", conversationId.toString()).update();
		this.jdbc.sql("""
				UPDATE conversation_state_events SET details = details - 'reason'
				WHERE tenant_id = :tenantId AND conversation_id = :id
			  AND details IS NOT NULL AND jsonb_exists(details, 'reason')
				""").param("tenantId", tenantId).param("id", conversationId).update();
		this.jdbc.sql("""
				UPDATE conversations SET purged_at = :now,
				       status = 'RESOLVED', resolved_at = COALESCE(resolved_at, :now),
				       archived_at = COALESCE(archived_at, :now),
				       archived_by = COALESCE(archived_by, 'system:retention'),
				       archive_reason = NULL, last_message_preview = NULL, version = version + 1,
				       updated_at = :now
				WHERE tenant_id = :tenantId AND id = :id
				""").param("now", now).param("tenantId", tenantId).param("id", conversationId).update();
	}

	public UUID startRun(String tenantId, boolean dryRun, OffsetDateTime cutoff, OffsetDateTime now) {
		return this.jdbc.sql("""
				INSERT INTO purge_runs (tenant_id, dry_run, cutoff, status, started_at)
				VALUES (:tenantId, :dryRun, :cutoff, 'RUNNING', :now) RETURNING id
				""")
			.param("tenantId", tenantId)
			.param("dryRun", dryRun)
			.param("cutoff", cutoff)
			.param("now", now)
			.query(UUID.class)
			.single();
	}

	public void finishRun(UUID runId, String status, int conversations, int messages, int attachments, int cursors,
			int batches, String error, OffsetDateTime finishedAt) {
		this.jdbc.sql("""
				UPDATE purge_runs SET status = :status, conversations_examined = :conversations,
				       messages_deleted = :messages, attachments_deleted = :attachments, cursors_deleted = :cursors,
				       batches = :batches, last_error = :error, finished_at = :finishedAt
				WHERE id = :id
				""")
			.param("status", status)
			.param("conversations", conversations)
			.param("messages", messages)
			.param("attachments", attachments)
			.param("cursors", cursors)
			.param("batches", batches)
			.param("error", error)
			.param("finishedAt", finishedAt)
			.param("id", runId)
			.update();
	}

	public Optional<PurgeRunView> findRun(UUID runId) {
		return this.jdbc.sql("""
				SELECT id, tenant_id, dry_run, cutoff, status, conversations_examined, messages_deleted,
				       attachments_deleted, cursors_deleted, batches, last_error, started_at, finished_at
				FROM purge_runs WHERE id = :id
				""").param("id", runId).query((rs, row) -> new PurgeRunView(rs.getObject("id", UUID.class),
						rs.getString("tenant_id"), rs.getBoolean("dry_run"),
						rs.getObject("cutoff", OffsetDateTime.class), rs.getString("status"),
						rs.getInt("conversations_examined"), rs.getInt("messages_deleted"),
						rs.getInt("attachments_deleted"), rs.getInt("cursors_deleted"), rs.getInt("batches"),
						rs.getString("last_error"), rs.getObject("started_at", OffsetDateTime.class),
						rs.getObject("finished_at", OffsetDateTime.class))).optional();
	}

	public void upsertPolicy(String tenantId, int retentionDays, boolean legalHold, OffsetDateTime now) {
		this.jdbc.sql("""
				INSERT INTO retention_policies (tenant_id, resolved_retention_days, legal_hold, updated_at)
				VALUES (:tenantId, :days, :hold, :now)
				ON CONFLICT (tenant_id) DO UPDATE SET resolved_retention_days = EXCLUDED.resolved_retention_days,
				       legal_hold = EXCLUDED.legal_hold, updated_at = EXCLUDED.updated_at
				""").param("tenantId", tenantId).param("days", retentionDays).param("hold", legalHold).param("now", now)
			.update();
	}

}
