package com.byteflock.chatplatform.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.chat.ChatDtos.Conversation;
import com.byteflock.chatplatform.chat.ChatDtos.Message;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ChatRepository {

	private static final String CONVERSATION_COLUMNS = """
			c.id, c.visitor_id, c.assigned_agent_id, c.status, c.created_at, c.updated_at, c.last_message_at,
			(SELECT left(m.body, 120) FROM chat_messages m WHERE m.conversation_id = c.id
			 ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_message_preview
			""";

	private final JdbcClient jdbc;

	ChatRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Race-safe: relies on the partial unique index on OPEN conversations per visitor. Empty if one exists. */
	Optional<UUID> insertOpenConversationIfAbsent(String visitorId, String agentId) {
		return this.jdbc.sql("""
				INSERT INTO chat_conversations (visitor_id, assigned_agent_id, status)
				VALUES (:visitor, :agent, 'OPEN')
				ON CONFLICT (visitor_id) WHERE status = 'OPEN' DO NOTHING
				RETURNING id
				""").param("visitor", visitorId).param("agent", agentId).query(UUID.class).optional();
	}

	Optional<Conversation> findOpenByVisitor(String visitorId) {
		return this.jdbc
			.sql("SELECT " + CONVERSATION_COLUMNS + " FROM chat_conversations c WHERE c.visitor_id = :visitor"
					+ " AND c.status = 'OPEN'")
			.param("visitor", visitorId)
			.query(ChatRepository::conversation)
			.optional();
	}

	Optional<Conversation> findById(UUID id) {
		return this.jdbc.sql("SELECT " + CONVERSATION_COLUMNS + " FROM chat_conversations c WHERE c.id = :id")
			.param("id", id)
			.query(ChatRepository::conversation)
			.optional();
	}

	List<Conversation> findByAgent(String agentId) {
		return this.jdbc
			.sql("SELECT " + CONVERSATION_COLUMNS + " FROM chat_conversations c WHERE c.assigned_agent_id = :agent"
					+ " ORDER BY COALESCE(c.last_message_at, c.created_at) DESC, c.id DESC")
			.param("agent", agentId)
			.query(ChatRepository::conversation)
			.list();
	}

	List<Message> findMessages(UUID conversationId) {
		return this.jdbc.sql("""
				SELECT id, conversation_id, sender_id, sender_role, body, created_at
				FROM chat_messages WHERE conversation_id = :id ORDER BY created_at, id
				""").param("id", conversationId).query(ChatRepository::message).list();
	}

	/** Must run inside the caller's transaction together with {@link #touchConversation}. */
	Message insertMessage(UUID conversationId, String senderId, String senderRole, String body) {
		return this.jdbc.sql("""
				INSERT INTO chat_messages (conversation_id, sender_id, sender_role, body)
				VALUES (:conversation, :sender, :role, :body)
				RETURNING id, conversation_id, sender_id, sender_role, body, created_at
				""")
			.param("conversation", conversationId)
			.param("sender", senderId)
			.param("role", senderRole)
			.param("body", body)
			.query(ChatRepository::message)
			.single();
	}

	void touchConversation(UUID conversationId, OffsetDateTime at) {
		this.jdbc.sql("UPDATE chat_conversations SET last_message_at = :at, updated_at = :at WHERE id = :id")
			.param("at", at)
			.param("id", conversationId)
			.update();
	}

	private static Conversation conversation(ResultSet rs, int row) throws SQLException {
		return new Conversation(rs.getObject("id", UUID.class), rs.getString("visitor_id"),
				rs.getString("assigned_agent_id"), rs.getString("status"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
				rs.getObject("last_message_at", OffsetDateTime.class), rs.getString("last_message_preview"));
	}

	private static Message message(ResultSet rs, int row) throws SQLException {
		return new Message(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
				rs.getString("sender_id"), rs.getString("sender_role"), rs.getString("body"),
				rs.getObject("created_at", OffsetDateTime.class));
	}

}
