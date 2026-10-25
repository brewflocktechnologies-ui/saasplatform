package com.byteflock.chatplatform.chat;

import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.CentrifugoStub;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Chat API against a disposable PostgreSQL (real V1+V2 migrations) and an in-process Centrifugo API stub.
 * Tests share one database, so assertions never depend on test order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ChatIntegrationTests {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24");

	static final CentrifugoStub centrifugo = new CentrifugoStub();

	@BeforeAll
	static void migrate() {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.schemas("platform")
			.locations("filesystem:" + System.getProperty("flyway.sql.dir", "../../database/flywayMigration/sql"))
			.load()
			.migrate();
	}

	@AfterAll
	static void stopStub() {
		centrifugo.close();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=platform");
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("app.centrifugo.url", centrifugo::url);
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	final JsonMapper json = JsonMapper.builder().build();

	UUID conversationId() {
		return UUID.fromString(body(this.mvc.post().uri("/api/v1/demo/conversations").exchange()).get("id").asString());
	}

	JsonNode body(MvcTestResult result) {
		return this.json.readTree(result.getResponse().getContentAsByteArray());
	}

	MvcTestResult send(UUID conversation, String actor, String text) {
		return this.mvc.post()
			.uri("/api/v1/demo/conversations/{id}/messages", conversation)
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(java.util.Map.of("actorId", actor, "text", text)))
			.exchange();
	}

	@Test
	void conversationCreationIsIdempotent() {
		UUID first = conversationId();
		assertThat(this.mvc.post().uri("/api/v1/demo/conversations")).hasStatus(200)
			.bodyJson()
			.extractingPath("$.id")
			.isEqualTo(first.toString());
		assertThat(this.jdbc.sql("SELECT count(*) FROM chat_conversations WHERE visitor_id = 'userone' AND status = 'OPEN'")
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void validMessagePersistsWithServerDerivedRoleAndIsPublishedAfterCommit() {
		UUID conversation = conversationId();
		MvcTestResult result = send(conversation, "userone", "  persisted hello  ");
		assertThat(result).hasStatus(201);
		JsonNode message = body(result).get("message");
		assertThat(body(result).get("realtimeDelivery").asString()).isEqualTo("PUBLISHED");
		assertThat(message.get("body").asString()).isEqualTo("persisted hello");
		assertThat(message.get("senderRole").asString()).isEqualTo("VISITOR");

		var row = this.jdbc.sql("SELECT conversation_id, sender_id, sender_role, body FROM chat_messages WHERE id = ?")
			.param(UUID.fromString(message.get("id").asString()))
			.query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
			.single();
		assertThat(row).containsExactly(conversation.toString(), "userone", "VISITOR", "persisted hello");

		String messageId = message.get("id").asString();
		assertThat(centrifugo.requests).anySatisfy((r) -> {
			JsonNode publish = this.json.readTree(r.body());
			assertThat(publish.get("channel").asString()).isEqualTo("chat:conversation:" + conversation);
			assertThat(publish.at("/data/type").asString()).isEqualTo("message.created");
			assertThat(publish.at("/data/message/id").asString()).isEqualTo(messageId);
		});
	}

	@Test
	void historyIsAscendingAndAgentRepliesAsAgent() {
		UUID conversation = conversationId();
		send(conversation, "userone", "order-1");
		send(conversation, "agentbob", "order-2");
		send(conversation, "userone", "order-3");

		JsonNode history = body(this.mvc.get()
			.uri("/api/v1/demo/conversations/{id}/messages?actorId=agentbob", conversation)
			.exchange());
		List<String> bodies = history.valueStream().map((m) -> m.get("body").asString())
			.filter((b) -> b.startsWith("order-"))
			.toList();
		assertThat(bodies).containsExactly("order-1", "order-2", "order-3");
		assertThat(history.valueStream().filter((m) -> m.get("body").asString().equals("order-2")).findFirst().orElseThrow()
			.get("senderRole").asString()).isEqualTo("AGENT");
	}

	@Test
	void invalidRequestsAreRejectedWithProblemDetails() {
		UUID conversation = conversationId();
		assertThat(send(conversation, "userone", "   ")).hasStatus(400)
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(send(conversation, "userone", "x".repeat(4001))).hasStatus(400);
		assertThat(send(conversation, "mallory", "hi")).hasStatus(400);
		assertThat(send(UUID.randomUUID(), "userone", "hi")).hasStatus(404);
		assertThat(this.mvc.get().uri("/api/v1/demo/conversations/not-a-uuid/messages?actorId=userone")).hasStatus(400);
		assertThat(this.mvc.get().uri("/api/v1/demo/conversations/{id}/messages", conversation)).hasStatus(400);
	}

	@Test
	void agentInboxComesFromTheDatabase() {
		UUID conversation = conversationId();
		send(conversation, "userone", "inbox-preview");
		assertThat(this.mvc.get().uri("/api/v1/demo/conversations?actorId=agentbob")).hasStatusOk()
			.bodyJson()
			.extractingPath("$[0].id")
			.isEqualTo(conversation.toString());
		assertThat(this.mvc.get().uri("/api/v1/demo/conversations?actorId=userone")).hasStatus(403);
		assertThat(this.mvc.get().uri("/api/v1/demo/conversations")).hasStatus(400);
	}

	@Test
	void subscriptionTokensAreScopedToActorAndChannel() {
		UUID conversation = conversationId();
		String url = "/api/v1/demo/realtime/subscription-token?actorId={a}&channel={c}";
		assertThat(this.mvc.get().uri(url, "agentbob", "chat:inbox")).hasStatusOk();
		assertThat(this.mvc.get().uri(url, "userone", "chat:conversation:" + conversation)).hasStatusOk();
		assertThat(this.mvc.get().uri(url, "agentbob", "chat:conversation:" + conversation)).hasStatusOk();
		assertThat(this.mvc.get().uri(url, "userone", "chat:inbox")).hasStatus(403);
		assertThat(this.mvc.get().uri(url, "userone", "chat:conversation:" + UUID.randomUUID())).hasStatus(404);
		assertThat(this.mvc.get().uri(url, "userone", "chat:conversation:../x")).hasStatus(400);
		assertThat(this.mvc.get().uri(url, "mallory", "chat:inbox")).hasStatus(400);
		assertThat(this.mvc.get().uri("/api/v1/demo/realtime/connection-token?actorId=userone")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.token")
			.isNotNull();
		assertThat(this.mvc.get().uri("/api/v1/demo/realtime/connection-token?actorId=root")).hasStatus(400);
	}

	@Test
	void databaseRejectsBlankBodiesAndOrphanMessages() {
		UUID conversation = conversationId();
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> this.jdbc
			.sql("INSERT INTO chat_messages (conversation_id, sender_id, sender_role, body) VALUES (?, 'userone', 'VISITOR', '  ')")
			.param(conversation)
			.update());
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> this.jdbc
			.sql("INSERT INTO chat_messages (conversation_id, sender_id, sender_role, body) VALUES (?, 'userone', 'VISITOR', 'x')")
			.param(UUID.randomUUID())
			.update());
	}

}
