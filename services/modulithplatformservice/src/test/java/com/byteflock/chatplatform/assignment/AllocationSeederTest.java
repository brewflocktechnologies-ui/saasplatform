package com.byteflock.chatplatform.assignment;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.byteflock.chatplatform.AllocationStub;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The allocation seeder: exact call order and bodies, idempotent re-runs, and publishing an existing draft policy. */
class AllocationSeederTest {

	static final String TENANT = "tenant-a";

	final AllocationStub stub = new AllocationStub();

	final JsonMapper json = JsonMapper.builder().build();

	final AllocationSeeder seeder = new AllocationSeeder(new AllocationClient(URI.create(this.stub.url()),
			Duration.ofSeconds(2), this.json, new SimpleMeterRegistry()));

	@AfterEach
	void stop() {
		this.stub.close();
	}

	@Test
	void createsMissingTenantQueueAgentAndPublishesPolicy() {
		this.seeder.ensureTenant(TENANT, "Tenant A", "support", List.of(new SeedAgent("agentbob", "Agent Bob", 3)));

		assertThat(this.stub.count("POST", "/api/v1/tenants")).isEqualTo(1);
		assertThat(this.stub.exact("POST", "/api/v1/queues")).isEqualTo(1);
		assertThat(this.stub.exact("POST", "/api/v1/agents")).isEqualTo(1);
		assertThat(this.stub.exact("POST", "/api/v1/policies")).isEqualTo(1);
		assertThat(this.stub.exact("POST", "/api/v1/policies/local-round-robin/publish")).isEqualTo(1);

		JsonNode agent = this.json.readTree(this.stub.requests("POST", "/api/v1/agents").getFirst().body());
		assertThat(agent.get("id").asString()).isEqualTo("agentbob");
		assertThat(agent.get("maxActiveAssignments").asInt()).isEqualTo(3);
		assertThat(agent.get("status").asString()).isEqualTo("OFFLINE");
		assertThat(agent.at("/memberships/0/queueId").asString()).isEqualTo("support");

		JsonNode policy = this.json.readTree(this.stub.requests("POST", "/api/v1/policies").getFirst().body());
		assertThat(policy.at("/queueIds/0").asString()).isEqualTo("support");
		assertThat(policy.get("maxTraceCandidates").asInt()).isEqualTo(200);
		assertThat(policy.at("/capacity/countedStates").toString()).contains("ASSIGNED", "ACCEPTED", "ACTIVE");
		assertThat(policy.at("/eligibility/filters").size()).isEqualTo(6);
	}

	@Test
	void isIdempotentWhenEverythingExists() {
		this.stub.respond("POST", "/api/v1/tenants", 409, "{\"code\":\"CONFLICT\"}");
		this.stub.respond("GET", "/api/v1/queues", 200, "[{\"id\":\"support\"}]");
		this.stub.respond("GET", "/api/v1/agents/agentbob", 200, this.stub.presence("agentbob", "ONLINE"));
		this.stub.respond("GET", "/api/v1/policies/local-round-robin", 200, "{\"state\":\"PUBLISHED\"}");

		this.seeder.ensureTenant(TENANT, "Tenant A", "support", List.of(new SeedAgent("agentbob", "Agent Bob", 3)));

		assertThat(this.stub.exact("POST", "/api/v1/queues")).isZero();
		assertThat(this.stub.exact("POST", "/api/v1/agents")).as("existing agents are never re-posted").isZero();
		assertThat(this.stub.count("GET", "/api/v1/agents/agentbob")).isEqualTo(1);
		assertThat(this.stub.exact("POST", "/api/v1/policies")).isZero();
		assertThat(this.stub.exact("POST", "/api/v1/policies/local-round-robin/publish")).isZero();
	}

	@Test
	void publishesExistingDraftPolicy() {
		this.stub.respond("GET", "/api/v1/queues", 200, "[{\"id\":\"support\"}]");
		this.stub.respond("GET", "/api/v1/agents/agentbob", 200, this.stub.presence("agentbob", "OFFLINE"));
		this.stub.respond("GET", "/api/v1/policies/local-round-robin", 200, "{\"state\":\"DRAFT\"}");

		this.seeder.ensureTenant(TENANT, "Tenant A", "support", List.of(new SeedAgent("agentbob", "Agent Bob", 3)));

		assertThat(this.stub.exact("POST", "/api/v1/policies")).isZero();
		assertThat(this.stub.exact("POST", "/api/v1/policies/local-round-robin/publish")).isEqualTo(1);
	}

}
