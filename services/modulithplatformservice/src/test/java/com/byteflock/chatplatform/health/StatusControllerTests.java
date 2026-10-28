package com.byteflock.chatplatform.health;

import com.byteflock.chatplatform.tenancy.SessionTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@WebMvcTest(StatusController.class)
class StatusControllerTests {

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	DependencyChecks checks;

	/** Metrics are not part of a MVC slice but the exception advice counts problem codes. */
	@MockitoBean
	io.micrometer.core.instrument.MeterRegistry meters;

	/** The tenancy web configuration is part of every MVC slice; its interceptor only needs the token verifier. */
	@MockitoBean
	SessionTokens sessionTokens;

	@Test
	void reportsUpWhenAllDependenciesAreUp() {
		given(this.checks.database()).willReturn(DependencyStatus.up("schema version 1"));
		given(this.checks.centrifugo()).willReturn(DependencyStatus.up("health endpoint OK"));
		given(this.checks.allocation()).willReturn(DependencyStatus.up("health endpoint OK"));

		assertThat(this.mvc.get().uri("/api/v1/status")).hasStatusOk()
			.bodyJson()
			.satisfies((json) -> {
				assertThat(json).extractingPath("$.status").isEqualTo("UP");
				assertThat(json).extractingPath("$.application").isEqualTo("modulithplatformservice");
				assertThat(json).extractingPath("$.dependencies.database.status").isEqualTo("UP");
			});
	}

	@Test
	void reportsDegradedWithoutLeakingDetailsWhenDatabaseIsDown() {
		given(this.checks.database()).willReturn(DependencyStatus.down("database unreachable or not migrated"));
		given(this.checks.centrifugo()).willReturn(DependencyStatus.up("health endpoint OK"));
		given(this.checks.allocation()).willReturn(DependencyStatus.up("health endpoint OK"));

		assertThat(this.mvc.get().uri("/api/v1/status")).hasStatusOk()
			.bodyJson()
			.satisfies((json) -> {
				assertThat(json).extractingPath("$.status").isEqualTo("DEGRADED");
				assertThat(json).extractingPath("$.dependencies.database.status").isEqualTo("DOWN");
			});
	}

	@Test
	void allowsConfiguredLocalOriginOnly() {
		given(this.checks.database()).willReturn(DependencyStatus.up(null));
		given(this.checks.centrifugo()).willReturn(DependencyStatus.up(null));
		given(this.checks.allocation()).willReturn(DependencyStatus.up(null));

		assertThat(this.mvc.get().uri("/api/v1/status").header("Origin", "http://localhost:3000"))
			.hasHeader("Access-Control-Allow-Origin", "http://localhost:3000");
		assertThat(this.mvc.get().uri("/api/v1/status").header("Origin", "http://evil.example")).hasStatus(403);
	}

}
