package com.byteflock.chatplatform.assignment;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live contract test against the real allocation service (container from Plan 04 T1): seed a unique tenant, then create,
 * dispatch, resolve and reopen one conversation. Skipped unless {@code ALLOCATION_URL} is set, so the normal suite never
 * needs the engine running.
 */
@EnabledIfEnvironmentVariable(named = "ALLOCATION_URL", matches = ".+")
class AllocationClientLiveIT {

	static final String TENANT = "it-" + System.currentTimeMillis();

	final AllocationClient client = new AllocationClient(URI.create(System.getenv("ALLOCATION_URL")),
			Duration.ofSeconds(3), JsonMapper.builder().build(), new SimpleMeterRegistry());

	final AllocationSeeder seeder = new AllocationSeeder(this.client);

	@Test
	void seedDispatchResolveReopenAgainstRealService() {
		this.seeder.ensureTenant(TENANT, "Integration tenant", "support",
				List.of(new SeedAgent("agentbob", "Agent Bob", 3)));
		this.client.presence(TENANT, "agentbob", Availability.ONLINE);
		String conversationId = UUID.randomUUID().toString();
		this.client.createConversation(TENANT, conversationId, "support", Instant.now());

		AllocationResult dispatched = this.client.dispatch(TENANT, "assign:" + conversationId + ":1", conversationId);

		assertThat(dispatched.outcome()).isEqualTo("ASSIGNED");
		assertThat(dispatched.agentId()).isEqualTo("agentbob");
		assertThat(this.client.findConversation(TENANT, conversationId).orElseThrow().status()).isEqualTo("ASSIGNED");

		this.client.resolve(TENANT, conversationId);
		assertThat(this.client.findConversation(TENANT, conversationId).orElseThrow().status()).isEqualTo("RESOLVED");

		this.client.requeueReopen(TENANT, conversationId);
		assertThat(this.client.findConversation(TENANT, conversationId).orElseThrow().status()).isEqualTo("QUEUED");

		assertThat(this.client.dispatch(TENANT, "assign:" + conversationId + ":2", conversationId).outcome())
			.isEqualTo("ASSIGNED");
	}

}
