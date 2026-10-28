package com.byteflock.chatplatform.conversation;

import java.time.Duration;
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

/** Manual assignment through the allocation service: claim, validation, stale version, archived/resolved, idempotent re-claim, outage, tenancy. */
class AssignmentApiIT extends PlatformIntegrationTest {

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
	void claimAssignsToCaller() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 404, "{}");

		MvcTestResult result = assign(fixture, conversation, "CLAIM", null, "claim-1");

		assertThat(result).hasStatusOk();
		assertThat(body(result).at("/assignment/state").asString()).isEqualTo("ASSIGNED");
		assertThat(body(result).at("/assignment/agentId").asString()).isEqualTo("agentbob");
		assertThat(ALLOCATION.requests("POST", "/api/v1/allocations/claim").getFirst().header("Idempotency-Key"))
			.isEqualTo("claim-1");
		assertThat(this.json.readTree(ALLOCATION.requests("POST", "/api/v1/allocations/claim").getFirst().body())
			.get("agentId").asString()).isEqualTo("agentbob");
		assertThat(this.data.outboxPayloads(conversation, "conversation.updated").get(0)).contains("ASSIGNED");
	}

	@Test
	void missingIdempotencyKeyIs400() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		assertThat(assign(fixture, conversation, "CLAIM", null, " ")).hasStatus(400);
		assertThat(ALLOCATION.count("POST", "/api/v1/allocations/claim")).isZero();
	}

	@Test
	void staleExpectedVersionIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		MvcTestResult result = assign(fixture, conversation, "CLAIM", this.data.version(conversation) - 1, "claim-1");

		assertThat(result).hasStatus(409);
		assertThat(body(result).get("code").asString()).isEqualTo("STALE_VERSION");
		assertThat(ALLOCATION.count("POST", "/api/v1/allocations/claim")).isZero();
	}

	@Test
	void assignOnArchivedIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, "done"));

		MvcTestResult result = assign(fixture, conversation, "CLAIM", null, "claim-1");

		assertThat(result).hasStatus(409);
		assertThat(body(result).get("code").asString()).isEqualTo("CONVERSATION_ARCHIVED");
		assertThat(ALLOCATION.count("POST", "/api/v1/allocations/claim")).isZero();
	}

	@Test
	void sameAgentReclaimIs200WithUnchangedVersion() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 404, "{}");
		assertThat(assign(fixture, conversation, "CLAIM", null, "claim-1")).hasStatusOk();
		long version = this.data.version(conversation);
		ALLOCATION.respond("POST", "/api/v1/allocations/claim", 200,
				ALLOCATION.decision("REJECTED_INVALID_INPUT", null).replace("\"reasonCodes\":[]",
						"\"reasonCodes\":[\"CONVERSATION_NOT_ALLOCATABLE\"]"));
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 200,
				"{\"status\":\"ASSIGNED\",\"assignedAgentId\":\"agentbob\"}");

		MvcTestResult result = assign(fixture, conversation, "CLAIM", null, "claim-2");

		assertThat(result).hasStatusOk();
		assertThat(body(result).at("/assignment/agentId").asString()).isEqualTo("agentbob");
		assertThat(this.data.version(conversation)).isEqualTo(version);
	}

	@Test
	void allocationDownIs503() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respondUnavailable(true);

		MvcTestResult result = assign(fixture, conversation, "CLAIM", null, "claim-1");

		assertThat(result).hasStatus(503);
		assertThat(body(result).get("code").asString()).isEqualTo("ALLOCATION_UNAVAILABLE");
	}

	@Test
	void crossTenantIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ConversationTestData.Fixture otherTenant = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		MvcTestResult result = assign(otherTenant, conversation, "CLAIM", null, "claim-1");

		assertThat(result).hasStatus(403);
	}

	private MvcTestResult assign(ConversationTestData.Fixture fixture, UUID conversation, String mode, Long version,
			String idempotencyKey) {
		String body = "{\"mode\":\"" + mode + "\"" + (version != null ? ",\"expectedVersion\":" + version : "") + "}";
		return this.mvc.post()
			.uri("/api/v1/agent/conversations/" + conversation + "/assign")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + agentToken(fixture))
			.header("Idempotency-Key", idempotencyKey)
			.content(body)
			.exchange();
	}

	private String agentToken(ConversationTestData.Fixture fixture) {
		return this.sessionTokens.issueAgent(fixture.tenantId(), "agentbob").token();
	}

}
