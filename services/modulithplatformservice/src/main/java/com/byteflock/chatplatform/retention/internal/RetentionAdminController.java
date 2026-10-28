package com.byteflock.chatplatform.retention.internal;

import java.util.UUID;

import com.byteflock.chatplatform.retention.PurgeRequest;
import com.byteflock.chatplatform.retention.PurgeRunView;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local-dev operator API for retention (master spec 16): dry-run/purge, inspect a run, set a tenant's policy.
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: `X-Admin-Token` and `app.local-dev.enabled=true` only.
 */
@RestController
@RequestMapping("/api/v1/admin/retention")
@ConditionalOnProperty(name = "app.local-dev.enabled", havingValue = "true")
class RetentionAdminController {

	private final RetentionService retention;

	RetentionAdminController(RetentionService retention) {
		this.retention = retention;
	}

	@PostMapping("/purge")
	@RequiresPrincipal(PrincipalType.ADMIN)
	PurgeRunView purge(@RequestBody(required = false) PurgeRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("a purge request body is required");
		}
		return this.retention.run(request);
	}

	@GetMapping("/runs/{runId}")
	@RequiresPrincipal(PrincipalType.ADMIN)
	PurgeRunView run(@PathVariable UUID runId) {
		return this.retention.find(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@PutMapping("/policies/{tenantId}")
	@RequiresPrincipal(PrincipalType.ADMIN)
	PolicyView policy(@PathVariable String tenantId, @RequestBody(required = false) PolicyRequest request) {
		if (request == null || request.resolvedRetentionDays() == null) {
			throw new IllegalArgumentException("resolvedRetentionDays is required");
		}
		boolean hold = Boolean.TRUE.equals(request.legalHold());
		this.retention.updatePolicy(tenantId, request.resolvedRetentionDays(), hold);
		return new PolicyView(tenantId, request.resolvedRetentionDays(), hold);
	}

	record PolicyRequest(Integer resolvedRetentionDays, Boolean legalHold) {
	}

	record PolicyView(String tenantId, int resolvedRetentionDays, boolean legalHold) {
	}

}
