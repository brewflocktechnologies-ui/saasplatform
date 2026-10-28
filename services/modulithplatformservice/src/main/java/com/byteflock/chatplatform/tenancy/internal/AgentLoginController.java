package com.byteflock.chatplatform.tenancy.internal;

import com.byteflock.chatplatform.tenancy.Agent;
import com.byteflock.chatplatform.tenancy.IssuedSession;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent local login ({@code app.local-dev.enabled=true} only): no password, no OIDC — it issues an agent session for an
 * agent that exists in the roster of the requested (or seeded) tenant.
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: replace with OIDC before any shared environment (ADR-0008).
 */
@RestController
@RequestMapping("/api/v1/agent/local-login")
@ConditionalOnProperty(name = "app.local-dev.enabled", havingValue = "true")
class AgentLoginController {

	private final TenantDirectory directory;

	private final SessionTokens sessionTokens;

	private final String defaultTenantId;

	AgentLoginController(TenantDirectory directory, SessionTokens sessionTokens,
			@Value("${app.seed.tenant-id}") String defaultTenantId) {
		this.directory = directory;
		this.sessionTokens = sessionTokens;
		this.defaultTenantId = defaultTenantId;
	}

	@PostMapping
	AgentSessionResponse login(@RequestBody(required = false) AgentLoginRequest request) {
		if (request == null || request.agentId() == null || request.agentId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		String tenantId = (request.tenantId() != null && !request.tenantId().isBlank()) ? request.tenantId()
				: this.defaultTenantId;
		Agent agent = this.directory.agent(tenantId, request.agentId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		IssuedSession session = this.sessionTokens.issueAgent(tenantId, agent.id());
		return new AgentSessionResponse(session.token(), session.expiresAt(), tenantId, agent.id(), agent.displayName());
	}

}
