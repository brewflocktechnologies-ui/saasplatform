package com.byteflock.chatplatform.outbox.internal;

import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.outbox.OutboxEventView;
import com.byteflock.chatplatform.outbox.OutboxStatus;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-dev operator API for the outbox (master spec 15): inspect rows by status and replay a parked event.
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: authenticated with {@code X-Admin-Token} and served only while
 * {@code app.local-dev.enabled=true}; production replaces it with a protected operator surface.
 */
@RestController
@RequestMapping("/api/v1/admin/outbox")
@ConditionalOnProperty(name = "app.local-dev.enabled", havingValue = "true")
class OutboxAdminController {

	private final OutboxRepository repository;

	OutboxAdminController(OutboxRepository repository) {
		this.repository = repository;
	}

	@GetMapping
	@RequiresPrincipal(PrincipalType.ADMIN)
	List<OutboxEventView> list(@RequestParam(name = "status", required = false) OutboxStatus status,
			@RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		return this.repository.list(status, Math.min(limit, 500));
	}

	@PostMapping("/{eventId}/replay")
	@RequiresPrincipal(PrincipalType.ADMIN)
	OutboxEventView replay(@PathVariable UUID eventId) {
		this.repository.replay(eventId);
		return this.repository.findByEventId(eventId)
			.map(OutboxAdminController::view)
			.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
	}

	private static OutboxEventView view(OutboxRepository.StoredEvent stored) {
		return new OutboxEventView(stored.eventId(), stored.tenantId(), stored.eventType(), stored.status(),
				stored.attempts(), stored.nextAttemptAt(), stored.lastError(), stored.createdAt(), stored.publishedAt());
	}

}
