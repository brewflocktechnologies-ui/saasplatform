package com.byteflock.chatplatform.conversation.internal;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Participant rows: the contact at creation, an agent on its first message or assignment (D14, Plan 04). */
@Repository
class ParticipantRepository {

	private final JdbcClient jdbc;

	ParticipantRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void ensure(UUID conversationId, String tenantId, String participantType, String participantId) {
		this.jdbc.sql("""
				INSERT INTO conversation_participants (conversation_id, tenant_id, participant_type, participant_id)
				VALUES (:conversationId, :tenantId, :participantType, :participantId)
				ON CONFLICT (conversation_id, participant_type, participant_id) DO NOTHING
				""")
			.param("conversationId", conversationId)
			.param("tenantId", tenantId)
			.param("participantType", participantType)
			.param("participantId", participantId)
			.update();
	}

}
