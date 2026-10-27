package com.byteflock.chatplatform.conversation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.MessagePage;
import com.byteflock.chatplatform.conversation.MessageView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Message persistence: idempotent insert, history pages, tenant-filtered reads. */
@Repository
class MessageRepository {

	private static final String COLUMNS = """
			id, conversation_id, sequence, sender_type, sender_id, message_type, body, client_message_id, created_at,
			edited_at, deleted_at
			""";

	private final JdbcClient jdbc;

	MessageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	MessageView insert(String tenantId, UUID conversationId, long sequence, String senderType, String senderId,
			String body, String clientMessageId, String fingerprint) {
		return this.jdbc.sql("INSERT INTO messages (tenant_id, conversation_id, sequence, sender_type, sender_id,"
				+ " message_type, body, client_message_id, request_fingerprint) "
				+ "VALUES (:tenantId, :conversationId, :sequence, :senderType, :senderId, 'TEXT', :body,"
				+ " :clientMessageId, :fingerprint) RETURNING " + COLUMNS)
			.param("tenantId", tenantId)
			.param("conversationId", conversationId)
			.param("sequence", sequence)
			.param("senderType", senderType)
			.param("senderId", senderId)
			.param("body", body)
			.param("clientMessageId", clientMessageId)
			.param("fingerprint", fingerprint)
			.query(MessageRepository::view)
			.single();
	}

	Optional<StoredMessage> findByClientMessageId(UUID conversationId, String senderType, String senderId,
			String clientMessageId) {
		return this.jdbc.sql("SELECT " + COLUMNS + ", request_fingerprint FROM messages"
				+ " WHERE conversation_id = :conversationId AND sender_type = :senderType"
				+ " AND sender_id = :senderId AND client_message_id = :clientMessageId")
			.param("conversationId", conversationId)
			.param("senderType", senderType)
			.param("senderId", senderId)
			.param("clientMessageId", clientMessageId)
			.query((rs, row) -> new StoredMessage(view(rs, row), rs.getString("request_fingerprint")))
			.optional();
	}

	Optional<MessageView> find(String tenantId, UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM messages WHERE tenant_id = :tenantId AND id = :id")
			.param("tenantId", tenantId)
			.param("id", id)
			.query(MessageRepository::view)
			.optional();
	}

	/** Older page: the newest {@code before} (or the newest page when {@code before} is null), ascending in the end. */
	MessagePage pageBefore(String tenantId, UUID conversationId, Long before, int limit) {
		List<MessageView> descending = this.jdbc.sql("SELECT " + COLUMNS + " FROM messages"
				+ " WHERE tenant_id = :tenantId AND conversation_id = :conversationId"
				+ " AND (CAST(:before AS bigint) IS NULL OR sequence < CAST(:before AS bigint))"
				+ " ORDER BY sequence DESC LIMIT :limit")
			.param("tenantId", tenantId)
			.param("conversationId", conversationId)
			.param("before", before, java.sql.Types.BIGINT)
			.param("limit", limit + 1)
			.query(MessageRepository::view)
			.list();
		boolean hasMore = descending.size() > limit;
		// The extra row is the oldest of the fetched window; drop it before reversing into an ascending page.
		List<MessageView> page = new ArrayList<>(descending.subList(0, Math.min(limit, descending.size())));
		java.util.Collections.reverse(page);
		return new MessagePage(List.copyOf(page), hasMore);
	}

	/** Catch-up page: everything newer than {@code after}, ascending. */
	MessagePage pageAfter(String tenantId, UUID conversationId, long after, int limit) {
		List<MessageView> ascending = this.jdbc.sql("SELECT " + COLUMNS + " FROM messages"
				+ " WHERE tenant_id = :tenantId AND conversation_id = :conversationId AND sequence > :after"
				+ " ORDER BY sequence ASC LIMIT :limit")
			.param("tenantId", tenantId)
			.param("conversationId", conversationId)
			.param("after", after)
			.param("limit", limit + 1)
			.query(MessageRepository::view)
			.list();
		boolean hasMore = ascending.size() > limit;
		return new MessagePage(List.copyOf(ascending.subList(0, Math.min(limit, ascending.size()))), hasMore);
	}

	/** A stored message plus the fingerprint of its body, for idempotent replay comparison (D5). */
	record StoredMessage(MessageView view, String fingerprint) {
	}

	static MessageView view(ResultSet rs, int row) throws SQLException {
		return new MessageView(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
				rs.getLong("sequence"), rs.getString("sender_type"), rs.getString("sender_id"),
				rs.getString("message_type"), rs.getString("body"), rs.getString("client_message_id"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getObject("edited_at", OffsetDateTime.class),
				rs.getObject("deleted_at", OffsetDateTime.class));
	}

}
