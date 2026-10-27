package com.byteflock.chatplatform.tenancy.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the local-development shortcuts are enabled in a production environment (ADR-0008).
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: {@code app.local-dev.enabled} turns on the agent local login, the admin token API
 * and the purge cutoff override; serving them in production would be an authentication bypass.
 */
@Component
public class LocalDevGuard {

	LocalDevGuard(@Value("${app.local-dev.enabled}") boolean localDevEnabled,
			@Value("${app.environment}") String environment) {
		if (localDevEnabled && "production".equalsIgnoreCase(environment)) {
			throw new IllegalStateException("app.local-dev.enabled must be false when app.environment=production");
		}
	}

}
