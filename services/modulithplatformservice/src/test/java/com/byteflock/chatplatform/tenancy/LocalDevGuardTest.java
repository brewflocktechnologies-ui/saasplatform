package com.byteflock.chatplatform.tenancy;

import com.byteflock.chatplatform.tenancy.internal.LocalDevGuard;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The local-development shortcuts must never be served in production (ADR-0008). */
class LocalDevGuardTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LocalDevGuard.class);

	@Test
	void failsStartupWhenLocalDevEnabledInProduction() {
		this.runner.withPropertyValues("app.environment=production", "app.local-dev.enabled=true")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasStackTraceContaining("app.local-dev.enabled must be false when app.environment=production");
			});
	}

	@Test
	void startsWhenLocalDevDisabledInProduction() {
		this.runner.withPropertyValues("app.environment=production", "app.local-dev.enabled=false")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startsWhenLocalDevEnabledLocally() {
		this.runner.withPropertyValues("app.environment=local", "app.local-dev.enabled=true")
			.run((context) -> assertThat(context).hasNotFailed());
	}

}
