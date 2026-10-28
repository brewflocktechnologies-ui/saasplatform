package com.byteflock.chatplatform.conversation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Presence goes through the allocation service; the widget's {@code agentOnline} is server-computed (D9, R-P4). */
class PresenceIT extends PlatformIntegrationTest {

	@Autowired
	ConversationService conversations;

	@Autowired
	TenantDirectory directory;

	@Autowired
	SessionTokens sessionTokens;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void putPresenceForwardsStatusWithoutHeartbeat() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ALLOCATION.setClock(Instant.now());
		ALLOCATION.respond("POST", "/api/v1/agents/agentbob/presence", 200, ALLOCATION.presence("agentbob", "ONLINE"));

		MvcTestResult result = this.mvc.put()
			.uri("/api/v1/agent/presence")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + agentToken(fixture))
			.content("{\"availability\":\"ONLINE\"}")
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(body(result).get("availability").asString()).isEqualTo("ONLINE");
		assertThat(body(result).get("agentId").asString()).isEqualTo("agentbob");
		assertThat(body(result).get("displayName").asString()).isEqualTo("Agent Bob");
		var engineBody = this.json
			.readTree(ALLOCATION.requests("POST", "/api/v1/agents/agentbob/presence").getFirst().body());
		assertThat(engineBody.get("status").asString()).isEqualTo("ONLINE");
		assertThat(engineBody.has("heartbeatAt")).isFalse();
	}

	@Test
	void meReturnsAllocationView() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ALLOCATION.setClock(Instant.now());
		ALLOCATION.respond("GET", "/api/v1/agents", 200, "[" + ALLOCATION.presence("agentbob", "AWAY") + "]");

		MvcTestResult result = this.mvc.get()
			.uri("/api/v1/agent/me")
			.header("Authorization", "Bearer " + agentToken(fixture))
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(body(result).get("agentId").asString()).isEqualTo("agentbob");
		assertThat(body(result).get("tenantId").asString()).isEqualTo(fixture.tenantId());
		assertThat(body(result).get("availability").asString()).isEqualTo("AWAY");
	}

	@Test
	void presenceWhenAllocationDownIs503() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ALLOCATION.respondUnavailable(true);

		assertThat(this.mvc.put()
			.uri("/api/v1/agent/presence")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + agentToken(fixture))
			.content("{\"availability\":\"ONLINE\"}")
			.exchange()).hasStatus(503);
	}

	@Test
	void widgetAgentOnlineReflectsFreshAndStaleHeartbeats() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.jdbc.sql("UPDATE conversations SET assignment_state = 'ASSIGNED', assigned_agent_id = 'agentbob' WHERE id = :id")
			.param("id", conversation)
			.update();
		String contactToken = this.sessionTokens
			.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();

		ALLOCATION.setClock(Instant.now());
		ALLOCATION.respond("GET", "/api/v1/agents", 200, "[" + ALLOCATION.presence("agentbob", "ONLINE") + "]");
		assertThat(body(get(conversation, contactToken)).get("agentOnline").asBoolean()).isTrue();

		ALLOCATION.setClock(Instant.now().minus(Duration.ofSeconds(61)));
		ALLOCATION.respond("GET", "/api/v1/agents", 200, "[" + ALLOCATION.presence("agentbob", "ONLINE") + "]");
		this.testClock.advance(Duration.ofSeconds(6));
		this.jdbc.sql("UPDATE conversations SET version = version + 1 WHERE id = :id").param("id", conversation).update();
		assertThat(body(get(conversation, contactToken)).get("agentOnline").asBoolean()).isFalse();
	}

	@Test
	void widgetAgentOnlineIsNullWhenAllocationIsDown() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.jdbc.sql("UPDATE conversations SET assignment_state = 'ASSIGNED', assigned_agent_id = 'agentbob' WHERE id = :id")
			.param("id", conversation)
			.update();
		String contactToken = this.sessionTokens
			.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();
		ALLOCATION.respondUnavailable(true);

		assertThat(body(get(conversation, contactToken)).get("agentOnline").isNull()).isTrue();
	}

	private MvcTestResult get(UUID conversation, String token) {
		return this.mvc.get()
			.uri("/api/v1/widget/conversations/" + conversation)
			.header("Authorization", "Bearer " + token)
			.exchange();
	}

	private String agentToken(ConversationTestData.Fixture fixture) {
		return this.sessionTokens.issueAgent(fixture.tenantId(), "agentbob").token();
	}

}
