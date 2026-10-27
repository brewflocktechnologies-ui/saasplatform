package com.byteflock.chatplatform.conversation.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only audit of lifecycle and assignment changes (R16). {@code details} never contains a message body; the
 * retention purge removes only the free-text reason (Plan 01 ruling R-P31 M10).
 */
@Repository
public class StateEventRepository {

	private final JdbcClient jdbc;

	public StateEventRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(UUID conversationId, String tenantId, String eventType, String actorType, String actorId,
			String fromStatus, String toStatus, String detailsJson) {
		this.jdbc.sql("""
				INSERT INTO conversation_state_events (conversation_id, tenant_id, event_type, actor_type, actor_id,
				                                       from_status, to_status, details)
				VALUES (:conversationId, :tenantId, :eventType, :actorType, :actorId, :fromStatus, :toStatus,
				        CAST(:details AS jsonb))
				""")
			.param("conversationId", conversationId)
			.param("tenantId", tenantId)
			.param("eventType", eventType)
			.param("actorType", actorType)
			.param("actorId", actorId)
			.param("fromStatus", fromStatus)
			.param("toStatus", toStatus)
			.param("details", detailsJson)
			.update();
	}

}
