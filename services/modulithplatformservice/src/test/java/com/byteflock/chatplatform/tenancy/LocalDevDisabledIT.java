package com.byteflock.chatplatform.tenancy;

import java.util.Map;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code app.local-dev.enabled=false} the agent local-login route does not exist at all: the agent is seeded, so a
 * 404 can only come from the missing mapping, not from an unknown agent.
 */
@ActiveProfiles("it-disabled")
class LocalDevDisabledIT extends PlatformIntegrationTest {

	@Autowired
	TenantDirectory directory;

	@Test
	void agentLocalLoginAbsentWhenLocalDevDisabled() {
		String tenantId = "tenant-disabled";
		this.directory.ensureTenant(tenantId, "Tenant disabled");
		this.directory.ensureAgent(tenantId, "agentbob", "Agent Bob");

		assertThat(this.mvc.post()
			.uri("/api/v1/agent/local-login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("agentId", "agentbob", "tenantId", tenantId)))
			.exchange()).hasStatus(404);
	}

	@org.springframework.test.context.DynamicPropertySource
	static void localDevDisabled(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("app.local-dev.enabled", () -> "false");
	}
}
