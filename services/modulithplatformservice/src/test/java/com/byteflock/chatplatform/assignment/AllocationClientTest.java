package com.byteflock.chatplatform.assignment;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.byteflock.chatplatform.AllocationStub;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** The typed allocation gateway: headers, idempotency keys, decision parsing, error classification (R-P4, R-P14, R-P17). */
class AllocationClientTest {

	static final String TENANT = "tenant-a";

	static final String CONVERSATION = "019a1b2c-0000-7000-8000-000000000001";

	final AllocationStub stub = new AllocationStub();

	final JsonMapper json = JsonMapper.builder().build();

	final AllocationClient client = new AllocationClient(URI.create(this.stub.url()), Duration.ofSeconds(2), this.json,
			new SimpleMeterRegistry());

	@AfterEach
	void stop() {
		this.stub.close();
	}

	@Test
	void sendsTenantRoleAndIdempotencyHeaders() {
		this.client.dispatch(TENANT, "assign:1", CONVERSATION);

		AllocationStub.Request request = this.stub.requests("POST", "/api/v1/allocations/dispatch").getFirst();
		assertThat(request.header("X-Tenant-Id")).isEqualTo(TENANT);
		assertThat(request.header("X-Role")).isEqualTo("operator");
		assertThat(request.header("Idempotency-Key")).isEqualTo("assign:1");
		assertThat(request.header("X-Correlation-Id")).isNotBlank();
	}

	@Test
	void findConversationMaps404ToEmptyAndParsesAssigned() {
		this.stub.respond("GET", "/api/v1/conversations/missing", 404, "{\"code\":\"CONVERSATION_NOT_FOUND\"}");
		assertThat(this.client.findConversation(TENANT, "missing")).isEmpty();

		this.stub.respond("GET", "/api/v1/conversations/" + CONVERSATION, 200,
				"{\"status\":\"ASSIGNED\",\"assignedAgentId\":\"agentbob\"}");
		AllocationConversationState state = this.client.findConversation(TENANT, CONVERSATION).orElseThrow();
		assertThat(state.status()).isEqualTo("ASSIGNED");
		assertThat(state.assignedAgentId()).isEqualTo("agentbob");
		assertThat(state.assigned()).isTrue();
	}

	@Test
	void createConversationIgnoresConversationExists() {
		this.stub.respond("POST", "/api/v1/conversations", 409, "{\"code\":\"CONVERSATION_EXISTS\"}");

		this.client.createConversation(TENANT, CONVERSATION, "support", Instant.parse("2026-10-27T10:00:00Z"));

		JsonNode body = this.json.readTree(this.stub.requests("POST", "/api/v1/conversations").getFirst().body());
		assertThat(body.get("conversationId").asString()).isEqualTo(CONVERSATION);
		assertThat(body.get("queueId").asString()).isEqualTo("support");
	}

	@Test
	void dispatchParsesDecision() {
		AllocationResult result = this.client.dispatch(TENANT, "assign:1", CONVERSATION);

		assertThat(result.outcome()).isEqualTo("ASSIGNED");
		assertThat(result.agentId()).isEqualTo("agentbob");
		assertThat(result.decisionId()).isNotNull();
		assertThat(result.explanation()).isEqualTo("stub decision");
		assertThat(result.reasonCodes()).isEmpty();
		assertThat(result.assigned()).isTrue();
	}

	@Test
	void claimSendsAgentId() {
		this.client.claim(TENANT, "claim:1", CONVERSATION, "agentbob");

		JsonNode body = this.json.readTree(this.stub.requests("POST", "/api/v1/allocations/claim").getFirst().body());
		assertThat(body.get("conversationId").asString()).isEqualTo(CONVERSATION);
		assertThat(body.get("agentId").asString()).isEqualTo("agentbob");
	}

	@Test
	void reassignUsesConversationPath() {
		this.client.reassign(TENANT, "reassign:1", CONVERSATION);

		assertThat(this.stub.count("POST", "/api/v1/conversations/" + CONVERSATION + "/reassign")).isEqualTo(1);
	}

	@Test
	void resolveSurfacesClosedAndNotFoundAsRejections() {
		this.stub.respond("POST", "/api/v1/conversations/" + CONVERSATION + "/resolve", 409,
				"{\"code\":\"CONVERSATION_CLOSED\"}");
		assertThatExceptionOfType(AllocationRejectedException.class)
			.isThrownBy(() -> this.client.resolve(TENANT, CONVERSATION))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(409);
				assertThat(ex.code()).isEqualTo("CONVERSATION_CLOSED");
			});

		this.stub.respond("POST", "/api/v1/conversations/" + CONVERSATION + "/resolve", 404,
				"{\"code\":\"TENANT_NOT_FOUND\"}");
		assertThatExceptionOfType(AllocationRejectedException.class)
			.isThrownBy(() -> this.client.resolve(TENANT, CONVERSATION))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("TENANT_NOT_FOUND"));
	}

	@Test
	void requeueReopenSendsReasonReopen() {
		this.client.requeueReopen(TENANT, CONVERSATION);

		JsonNode body = this.json
			.readTree(this.stub.requests("POST", "/api/v1/conversations/" + CONVERSATION + "/requeue").getFirst().body());
		assertThat(body.get("reason").asString()).isEqualTo("REOPEN");
		assertThat(body.get("excludePreviousAgent").asBoolean()).isFalse();
	}

	@Test
	void presencePostsStatusWithoutHeartbeat() {
		this.stub.respond("POST", "/api/v1/agents/agentbob/presence", 200, this.stub.presence("agentbob", "ONLINE"));

		AgentPresenceView view = this.client.presence(TENANT, "agentbob", Availability.ONLINE);

		JsonNode body = this.json.readTree(this.stub.requests("POST", "/api/v1/agents/agentbob/presence").getFirst()
			.body());
		assertThat(body.get("status").asString()).isEqualTo("ONLINE");
		assertThat(body.has("heartbeatAt")).isFalse();
		assertThat(view.availability()).isEqualTo(Availability.ONLINE);
		assertThat(view.lastHeartbeatAt()).isNotNull();
		assertThat(view.enabled()).isTrue();
	}

	@Test
	void agentsListsPresence() {
		this.stub.respond("GET", "/api/v1/agents", 200, "[" + this.stub.presence("agentbob", "AWAY") + "]");

		List<AgentPresenceView> agents = this.client.agents(TENANT);

		assertThat(agents).hasSize(1);
		assertThat(agents.getFirst().agentId()).isEqualTo("agentbob");
		assertThat(agents.getFirst().availability()).isEqualTo(Availability.AWAY);
	}

	@Test
	void serverErrorIsUnavailable() {
		this.stub.respond("POST", "/api/v1/allocations/dispatch", 503, "{}");

		assertThatExceptionOfType(AllocationUnavailableException.class)
			.isThrownBy(() -> this.client.dispatch(TENANT, "assign:1", CONVERSATION));
	}

	@Test
	void timeoutIsUnavailable() {
		this.stub.delayNext(Duration.ofSeconds(4));

		Instant started = Instant.now();
		assertThatExceptionOfType(AllocationUnavailableException.class)
			.isThrownBy(() -> this.client.dispatch(TENANT, "assign:1", CONVERSATION));
		assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void badRequestIsRejectedWithCode() {
		this.stub.respond("POST", "/api/v1/allocations/dispatch", 400, "{\"code\":\"INVALID_ID\"}");

		assertThatExceptionOfType(AllocationRejectedException.class)
			.isThrownBy(() -> this.client.dispatch(TENANT, "assign:1", CONVERSATION))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("INVALID_ID"));
	}

	@Test
	void healthyReflectsHealthEndpoint() {
		assertThat(this.client.healthy()).isTrue();

		this.stub.respondUnavailable(true);
		assertThat(this.client.healthy()).isFalse();
	}

}
