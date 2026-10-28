package com.byteflock.chatplatform.tenancy;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only routes carrying the production annotations, imported by the tenancy integration tests: they prove the caller
 * interceptor and argument resolver without depending on the conversation endpoints of Plan 02 T4.
 */
@RestController
@RequestMapping("/api/v1/widget/probe")
class WidgetProbeController {

	@GetMapping
	@RequiresPrincipal(PrincipalType.CONTACT)
	Map<String, String> get(Caller caller) {
		return Map.of("tenantId", caller.tenantId(), "type", caller.type().name(), "principalId",
				caller.principalId(), "inboxId", caller.inboxId().toString());
	}

}

@RestController
@RequestMapping("/api/v1/agent/probe")
class AgentProbeController {

	@GetMapping
	@RequiresPrincipal(PrincipalType.AGENT)
	Map<String, String> get(Caller caller) {
		return Map.of("tenantId", caller.tenantId(), "type", caller.type().name(), "principalId", caller.principalId());
	}

}

@RestController
@RequestMapping("/api/v1/admin/ping")
class AdminProbeController {

	@GetMapping
	@RequiresPrincipal(PrincipalType.ADMIN)
	Map<String, String> get(Caller caller) {
		return Map.of("principalId", caller.principalId(), "type", caller.type().name(),
				"tenantId", (caller.tenantId() != null) ? caller.tenantId() : "");
	}

}
