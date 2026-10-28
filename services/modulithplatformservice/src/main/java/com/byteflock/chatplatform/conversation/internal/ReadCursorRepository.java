package com.byteflock.chatplatform.conversation.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read cursors: one row per participant, monotonic and never past {@code last_sequence} (D13, R12). */
@Repository
class ReadCursorRepository {

	private final JdbcClient jdbc;

	ReadCursorRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Upsert with {@code GREATEST}, so a concurrent or repeated write never moves the cursor backwards. */
	void advance(UUID conversationId, String tenantId, String participantType, String participantId, long sequence) {
		this.jdbc.sql("""
				INSERT INTO conversation_read_cursors (conversation_id, tenant_id, participant_type, participant_id,
				                                       last_read_sequence, updated_at)
				VALUES (:conversationId, :tenantId, :participantType, :participantId, :sequence, clock_timestamp())
				ON CONFLICT (conversation_id, participant_type, participant_id)
				DO UPDATE SET last_read_sequence = GREATEST(conversation_read_cursors.last_read_sequence,
				                                            EXCLUDED.last_read_sequence),
				              updated_at = clock_timestamp()
				""")
			.param("conversationId", conversationId)
			.param("tenantId", tenantId)
			.param("participantType", participantType)
			.param("participantId", participantId)
			.param("sequence", sequence)
			.update();
	}

}
