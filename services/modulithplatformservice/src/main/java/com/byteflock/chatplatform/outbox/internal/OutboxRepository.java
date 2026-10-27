package com.byteflock.chatplatform.outbox.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
				       created_at, published_at
				FROM outbox_events WHERE event_id = :eventId
				""").param("eventId", eventId).query(OutboxRepository::storedEvent).optional();
	}

	public List<StoredEvent> findByAggregate(String tenantId, String aggregateType, String aggregateId) {
		return this.jdbc.sql("""
				SELECT event_id, tenant_id, event_type, status, attempts, payload, channels, next_attempt_at,
				       created_at, published_at
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

	public record StoredEvent(UUID eventId, String tenantId, String eventType, OutboxStatus status, int attempts,
			String payload, List<String> channels, OffsetDateTime nextAttemptAt, OffsetDateTime createdAt,
			OffsetDateTime publishedAt) {
	}

	private static StoredEvent storedEvent(ResultSet rs, int row) throws SQLException {
		return new StoredEvent(rs.getObject("event_id", UUID.class), rs.getString("tenant_id"),
				rs.getString("event_type"), OutboxStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
				rs.getString("payload"), List.of((String[]) rs.getArray("channels").getArray()),
				rs.getObject("next_attempt_at", OffsetDateTime.class),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("published_at", OffsetDateTime.class));
	}

}
