package com.byteflock.chatplatform.assignment;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin tenant seeding: platform rows plus the allocation-side tenant, queue, agents and published policy. */
class AdminTenantIT extends PlatformIntegrationTest {

	static final String TENANT = "tenant-admin-" + System.currentTimeMillis();

	@Test
	void createsTenantAndSeedsAllocation() {
		MvcTestResult result = create();

		assertThat(result).hasStatus(201);
		assertThat(body(result).get("id").asString()).isEqualTo(TENANT);
		assertThat(body(result).get("agents").asInt()).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT count(*) FROM inboxes WHERE widget_key = :key")
			.param("key", "wk-" + TENANT)
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT count(*) FROM agents WHERE tenant_id = :id AND id = 'agentbob'")
			.param("id", TENANT)
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(ALLOCATION.count("POST", "/api/v1/tenants")).isEqualTo(1);
		assertThat(ALLOCATION.count("POST", "/api/v1/policies/local-round-robin/publish")).isEqualTo(1);
	}

	@Test
	void secondCallIsIdempotent200() {
		assertThat(create()).hasStatus(201);
		assertThat(create()).hasStatus(200);
		assertThat(this.jdbc.sql("SELECT count(*) FROM tenants WHERE id = :id")
			.param("id", TENANT)
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	private MvcTestResult create() {
		return this.mvc.post()
			.uri("/api/v1/admin/tenants")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Admin-Token", ADMIN_TOKEN)
			.content("{\"id\":\"" + TENANT + "\",\"name\":\"Admin tenant\",\"widgetKey\":\"wk-" + TENANT
					+ "\",\"queueId\":\"support\",\"agents\":[{\"id\":\"agentbob\",\"displayName\":\"Agent Bob\","
					+ "\"maxActiveAssignments\":3}]}")
			.exchange();
	}

}
