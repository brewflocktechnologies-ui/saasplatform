package com.byteflock.chatplatform.outbox.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.outbox.OutboxEventView;
import com.byteflock.chatplatform.outbox.OutboxStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Outbox persistence. Plan 03 adds the claiming and marking statements of the publisher worker; this plan inserts rows
 * inside the change transaction and reads them for assertions.
 * <p>
 * The insert takes {@code next_attempt_at} from the caller's {@code Clock} rather than the column default, so a fresh
 * row is never "in the future" because of JVM/container clock skew.
 */
@Repository
public class OutboxRepository {

	private final JdbcClient jdbc;

	public OutboxRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public UUID nextEventId() {
		return this.jdbc.sql("SELECT uuidv7()").query(UUID.class).single();
	}

	public void insert(OutboxRecord record) {
		this.jdbc.sql("""
				INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id, event_type, schema_version,
				                           channels, payload, next_attempt_at, trace_id)
				VALUES (:eventId, :tenantId, :aggregateType, :aggregateId, :eventType, :schemaVersion,
				        :channels, CAST(:payload AS jsonb), :nextAttemptAt, :traceId)
				""")
			.param("eventId", record.eventId())
			.param("tenantId", record.tenantId())
			.param("aggregateType", record.aggregateType())
			.param("aggregateId", record.aggregateId())
			.param("eventType", record.eventType())
			.param("schemaVersion", record.schemaVersion())
			.param("channels", record.channels().toArray(String[]::new))
			.param("payload", record.payload())
			.param("nextAttemptAt", record.nextAttemptAt())
			.param("traceId", record.traceId())
			.update();
	}

	public Optional<StoredEvent> findByEventId(UUID eventId) {
		return this.jdbc.sql("""
				SELECT event_id, tenant_id, event_type, status, attempts, payload, channels, next_attempt_at,
				       last_error, created_at, published_at
				FROM outbox_events WHERE event_id = :eventId
				""").param("eventId", eventId).query(OutboxRepository::storedEvent).optional();
	}

	public List<StoredEvent> findByAggregate(String tenantId, String aggregateType, String aggregateId) {
		return this.jdbc.sql("""
				SELECT event_id, tenant_id, event_type, status, attempts, payload, channels, next_attempt_at,
				       last_error, created_at, published_at
				FROM outbox_events
				WHERE tenant_id = :tenantId AND aggregate_type = :aggregateType AND aggregate_id = :aggregateId
				ORDER BY created_at, event_id
				""")
			.param("tenantId", tenantId)
			.param("aggregateType", aggregateType)
			.param("aggregateId", aggregateId)
			.query(OutboxRepository::storedEvent)
			.list();
	}

	public int countByStatus(OutboxStatus status) {
		return this.jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = :status")
			.param("status", status.name())
			.query(Integer.class)
			.single();
	}

	/**
	 * Claims a batch with a lease: {@code FOR UPDATE SKIP LOCKED} inside a {@code MATERIALIZED} CTE so PostgreSQL
	 * evaluates the locked, limited subquery once (Plan 01 ruling R-P31 M16). The attempt is counted at claim time, so a
	 * worker that crashes after claiming still uses it up.
	 */
	public List<OutboxRow> claimBatch(String workerId, int limit, OffsetDateTime now, Duration lease) {
		return this.jdbc.sql("""
				WITH due AS MATERIALIZED (
				    SELECT event_id FROM outbox_events
				    WHERE status = 'PENDING'
				      AND next_attempt_at <= :now
				      AND (locked_until IS NULL OR locked_until < :now)
				    ORDER BY next_attempt_at, created_at
				    LIMIT :limit
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE outbox_events o
				SET locked_by = :workerId, locked_until = :lockedUntil, attempts = attempts + 1
				FROM due WHERE o.event_id = due.event_id
				RETURNING o.event_id, o.tenant_id, o.event_type, o.channels, o.payload, o.attempts, o.locked_until,
				          o.created_at, o.trace_id
				""")
			.param("now", now)
			.param("limit", limit)
			.param("workerId", workerId)
			.param("lockedUntil", now.plus(lease))
			.query(OutboxRepository::row)
			.list();
	}

	/** Marks a row published; 0 rows means the lease was lost and the mark is ignored. */
	public int markPublished(UUID eventId, String workerId, OffsetDateTime now) {
		return this.jdbc.sql("""
				UPDATE outbox_events
				SET status = 'PUBLISHED', published_at = :now, locked_by = NULL, locked_until = NULL, last_error = NULL
				WHERE event_id = :eventId AND locked_by = :workerId
				""")
			.param("now", now)
			.param("eventId", eventId)
			.param("workerId", workerId)
			.update();
	}

	/** Releases a row for a retry, or parks it after a non-transport rejection (Plan 01 ruling R-P30). */
	public int markFailed(UUID eventId, String workerId, OffsetDateTime nextAttemptAt, String error, boolean parked) {
		return this.jdbc.sql("""
				UPDATE outbox_events
				SET status = :status, next_attempt_at = :nextAttemptAt, last_error = :error,
				    locked_by = NULL, locked_until = NULL
				WHERE event_id = :eventId AND locked_by = :workerId
				""")
			.param("status", parked ? OutboxStatus.PARKED.name() : OutboxStatus.PENDING.name())
			.param("nextAttemptAt", nextAttemptAt)
			.param("error", error)
			.param("eventId", eventId)
			.param("workerId", workerId)
			.update();
	}

	/** Puts a parked row back into the queue for the admin replay endpoint. */
	public int replay(UUID eventId) {
		return this.jdbc.sql("""
				UPDATE outbox_events
				SET status = 'PENDING', attempts = 0, next_attempt_at = clock_timestamp(), last_error = NULL,
				    locked_by = NULL, locked_until = NULL
				WHERE event_id = :eventId AND status = 'PARKED'
				""").param("eventId", eventId).update();
	}

	public Optional<Instant> oldestPendingCreatedAt() {
		return this.jdbc.sql("SELECT min(created_at) FROM outbox_events WHERE status = 'PENDING'")
			.query(Instant.class)
			.optional();
	}

	public List<OutboxEventView> list(OutboxStatus status, int limit) {
		String filter = (status != null) ? "WHERE status = :status" : "";
		JdbcClient.StatementSpec statement = this.jdbc.sql("""
				SELECT event_id, tenant_id, event_type, status, attempts, next_attempt_at, last_error, created_at,
				       published_at
				FROM outbox_events
				""" + filter + " ORDER BY created_at DESC, event_id LIMIT :limit").param("limit", limit);
		if (status != null) {
			statement = statement.param("status", status.name());
		}
		return statement.query((rs, row) -> new OutboxEventView(rs.getObject("event_id", UUID.class),
				rs.getString("tenant_id"), rs.getString("event_type"),
				OutboxStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
				rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getString("last_error"),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("published_at", OffsetDateTime.class))).list();
	}

	public record OutboxRow(UUID eventId, String tenantId, String eventType, List<String> channels, String payload,
			int attempts, OffsetDateTime lockedUntil, OffsetDateTime createdAt, String traceId) {
	}

	private static OutboxRow row(ResultSet rs, int row) throws SQLException {
		return new OutboxRow(rs.getObject("event_id", UUID.class), rs.getString("tenant_id"),
				rs.getString("event_type"), List.of((String[]) rs.getArray("channels").getArray()),
				rs.getString("payload"), rs.getInt("attempts"), rs.getObject("locked_until", OffsetDateTime.class),
				rs.getObject("created_at", OffsetDateTime.class), rs.getString("trace_id"));
	}

	public record StoredEvent(UUID eventId, String tenantId, String eventType, OutboxStatus status, int attempts,
			String payload, List<String> channels, OffsetDateTime nextAttemptAt, String lastError,
			OffsetDateTime createdAt, OffsetDateTime publishedAt) {
	}

	private static StoredEvent storedEvent(ResultSet rs, int row) throws SQLException {
		return new StoredEvent(rs.getObject("event_id", UUID.class), rs.getString("tenant_id"),
				rs.getString("event_type"), OutboxStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
				rs.getString("payload"), List.of((String[]) rs.getArray("channels").getArray()),
				rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getString("last_error"),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("published_at", OffsetDateTime.class));
	}

}
