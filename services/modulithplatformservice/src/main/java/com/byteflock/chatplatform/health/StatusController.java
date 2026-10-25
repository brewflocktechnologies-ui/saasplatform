package com.byteflock.chatplatform.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/status")
class StatusController {

	private final DependencyChecks checks;

	private final String application;

	private final String version;

	private final String environment;

	StatusController(DependencyChecks checks, @Value("${spring.application.name}") String application,
			@Value("${app.version}") String version, @Value("${app.environment}") String environment) {
		this.checks = checks;
		this.application = application;
		this.version = version;
		this.environment = environment;
	}

	@GetMapping
	StatusResponse status() {
		Map<String, DependencyStatus> dependencies = new LinkedHashMap<>();
		dependencies.put("database", this.checks.database());
		dependencies.put("centrifugo", this.checks.centrifugo());
		String overall = dependencies.values().stream().allMatch(DependencyStatus::isUp) ? "UP" : "DEGRADED";
		return new StatusResponse(this.application, this.version, this.environment, overall, Instant.now(),
				dependencies);
	}

}
