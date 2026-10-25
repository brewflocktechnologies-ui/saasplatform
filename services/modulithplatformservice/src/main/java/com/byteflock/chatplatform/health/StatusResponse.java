package com.byteflock.chatplatform.health;

import java.time.Instant;
import java.util.Map;

public record StatusResponse(String application, String version, String environment, String status, Instant timestamp,
		Map<String, DependencyStatus> dependencies) {
}
