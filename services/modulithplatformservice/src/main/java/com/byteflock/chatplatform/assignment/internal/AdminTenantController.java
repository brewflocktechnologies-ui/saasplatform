package com.byteflock.chatplatform.assignment.internal;

import java.util.List;

import com.byteflock.chatplatform.assignment.AllocationSeeder;
import com.byteflock.chatplatform.assignment.SeedAgent;
import com.byteflock.chatplatform.tenancy.Inbox;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin seeding endpoint: creates or updates a tenant with one inbox and its agents, then seeds the allocation side
 * (tenant, queue, agents, published policy). Idempotent: 201 when the inbox is new, 200 when it already existed.
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: `X-Admin-Token` and `app.local-dev.enabled=true` only.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@ConditionalOnProperty(name = "app.local-dev.enabled", havingValue = "true")
class AdminTenantController {

	private final TenantDirectory directory;

	private final AllocationSeeder allocationSeeder;

	AdminTenantController(TenantDirectory directory, AllocationSeeder allocationSeeder) {
		this.directory = directory;
		this.allocationSeeder = allocationSeeder;
	}

	@PostMapping
	@RequiresPrincipal(PrincipalType.ADMIN)
	ResponseEntity<TenantView> create(@RequestBody(required = false) TenantRequest request) {
		if (request == null || blank(request.id()) || blank(request.name()) || blank(request.widgetKey())
				|| blank(request.queueId())) {
			throw new IllegalArgumentException("id, name, widgetKey and queueId are required");
		}
		List<TenantRequest.SeedAgentRequest> agents = (request.agents() != null) ? request.agents() : List.of();
		boolean existed = this.directory.inboxByWidgetKey(request.widgetKey()).isPresent();
		this.directory.ensureTenant(request.id(), request.name());
		Inbox inbox = this.directory.ensureInbox(request.id(), "Website", request.widgetKey(), request.queueId());
		List<SeedAgent> seedAgents = agents.stream()
			.map((agent) -> new SeedAgent(agent.id(), agent.displayName(),
					(agent.maxActiveAssignments() != null) ? agent.maxActiveAssignments() : 3))
			.toList();
		for (SeedAgent agent : seedAgents) {
			this.directory.ensureAgent(request.id(), agent.id(), agent.displayName());
		}
		this.allocationSeeder.ensureTenant(request.id(), request.name(), request.queueId(), seedAgents);
		return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED)
			.body(new TenantView(request.id(), request.name(), inbox.id(), request.widgetKey(), request.queueId(),
					seedAgents.size()));
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	record TenantRequest(String id, String name, String widgetKey, String queueId, List<SeedAgentRequest> agents) {

		record SeedAgentRequest(String id, String displayName, Integer maxActiveAssignments) {
		}

	}

	record TenantView(String id, String name, java.util.UUID inboxId, String widgetKey, String queueId,
			int agents) {
	}

}
