package com.byteflock.chatplatform.assignment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Typed HTTP gateway to the allocation service (ADR-0009). Every call carries {@code X-Tenant-Id}, {@code X-Role} and a
 * correlation id; dispatch, claim and reassign also require an {@code Idempotency-Key}.
 * <p>
 * Failure classification (R-P14, R-P17, R-P30): connection errors, timeouts and 5xx are
 * {@link AllocationUnavailableException} (transient); any other 4xx is {@link AllocationRejectedException} with the
 * engine's status and code, and the caller decides what it means. The engine returns HTTP 200 with an
 * {@link AllocationResult} for every business outcome, so outcomes are never inferred from the status.
 */
@Component
public class AllocationClient {

	private static final String OPERATOR = "operator";

	private static final String ADMIN = "admin";

	private final URI baseUrl;

	private final Duration timeout;

	private final JsonMapper json;

	private final MeterRegistry meters;

	private final HttpClient http;

	public AllocationClient(@Value("${app.allocation.url}") URI baseUrl,
			@Value("${app.allocation.timeout}") Duration timeout, JsonMapper json, MeterRegistry meters) {
		this.baseUrl = baseUrl;
		this.timeout = timeout;
		this.json = json;
		this.meters = meters;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	public Optional<AllocationConversationState> findConversation(String tenantId, String conversationId) {
		return call("findConversation", () -> {
			HttpResult result = send("GET", "/api/v1/conversations/" + conversationId, OPERATOR, tenantId, null, null);
			if (result.status() == 404) {
				return Optional.empty();
			}
			requireSuccess(result);
			return Optional.of(new AllocationConversationState(text(result.body(), "status"),
					text(result.body(), "assignedAgentId")));
		});
	}

	/** Creates the allocation record; an existing record (409) is fine. */
	public void createConversation(String tenantId, String conversationId, String queueId, Instant createdAt) {
		call("createConversation", () -> {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("conversationId", conversationId);
			body.put("queueId", queueId);
			body.put("createdAt", createdAt.toString());
			body.put("enqueuedAt", createdAt.toString());
			HttpResult result = send("POST", "/api/v1/conversations", OPERATOR, tenantId, null, body);
			if (result.status() == 409) {
				return null;
			}
			requireSuccess(result);
			return null;
		});
	}

	public AllocationResult dispatch(String tenantId, String idempotencyKey, String conversationId) {
		return call("dispatch", () -> decision(send("POST", "/api/v1/allocations/dispatch", OPERATOR, tenantId,
				idempotencyKey, Map.of("conversationId", conversationId))));
	}

	public AllocationResult claim(String tenantId, String idempotencyKey, String conversationId, String agentId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("conversationId", conversationId);
		body.put("agentId", agentId);
		return call("claim", () -> decision(
				send("POST", "/api/v1/allocations/claim", OPERATOR, tenantId, idempotencyKey, body)));
	}

	public AllocationResult reassign(String tenantId, String idempotencyKey, String conversationId) {
		return call("reassign", () -> decision(send("POST", "/api/v1/conversations/" + conversationId + "/reassign",
				OPERATOR, tenantId, idempotencyKey, null)));
	}

	/** Resolves in the engine. 404 and 409 are surfaced as rejections: the orchestrator confirms with a read. */
	public void resolve(String tenantId, String conversationId) {
		call("resolve", () -> {
			HttpResult result = send("POST", "/api/v1/conversations/" + conversationId + "/resolve", OPERATOR, tenantId,
					null, null);
			if (result.status() == 409) {
				requireSuccess(result);
			}
			requireSuccess(result);
			return null;
		});
	}

	/** Requeues a resolved conversation; {@code excludePreviousAgent=false} so a single-agent tenant can be re-assigned. */
	public void requeueReopen(String tenantId, String conversationId) {
		call("requeueReopen", () -> {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("reason", "REOPEN");
			body.put("excludePreviousAgent", false);
			HttpResult result = send("POST", "/api/v1/conversations/" + conversationId + "/requeue", OPERATOR, tenantId,
					null, body);
			if (result.status() == 409 && "INVALID_REQUEUE".equals(code(result.body()))) {
				// Already queued (for example by the sweep): nothing left to do.
				Optional<AllocationConversationState> state = findConversation(tenantId, conversationId);
				if (state.isPresent() && "QUEUED".equals(state.get().status())) {
					return null;
				}
			}
			requireSuccess(result);
			return null;
		});
	}

	/** Forwards availability only; the engine stamps its own heartbeat, so clock skew cannot cause TIMESTAMP_IN_FUTURE. */
	public AgentPresenceView presence(String tenantId, String agentId, Availability status) {
		return call("presence", () -> {
			HttpResult result = send("POST", "/api/v1/agents/" + agentId + "/presence", OPERATOR, tenantId, null,
					Map.of("status", status.name()));
			requireSuccess(result);
			return presenceView(result.body(), agentId);
		});
	}

	public List<AgentPresenceView> agents(String tenantId) {
		return call("agents", () -> {
			HttpResult result = send("GET", "/api/v1/agents", OPERATOR, tenantId, null, null);
			requireSuccess(result);
			List<AgentPresenceView> agents = new ArrayList<>();
			for (JsonNode agent : result.body()) {
				agents.add(presenceView(agent, text(agent, "id")));
			}
			return agents;
		});
	}

	public boolean healthy() {
		try {
			HttpResult result = send("GET", "/api/v1/health", OPERATOR, "platform", null, null);
			return result.status() == 200;
		}
		catch (AllocationUnavailableException ex) {
			return false;
		}
	}

	// --- seeder support (same module) -------------------------------------------------------------------------------

	HttpResult adminGet(String tenantId, String path) {
		return call("seeder", () -> send("GET", path, ADMIN, tenantId, null, null));
	}

	HttpResult adminPost(String tenantId, String path, Object body) {
		return call("seeder", () -> send("POST", path, ADMIN, tenantId, null, body));
	}

	HttpResult operatorGet(String tenantId, String path) {
		return call("seeder", () -> send("GET", path, OPERATOR, tenantId, null, null));
	}

	// --- internals --------------------------------------------------------------------------------------------------

	private AllocationResult decision(HttpResult result) {
		requireSuccess(result);
		JsonNode body = result.body();
		JsonNode reasonCodes = body.get("reasonCodes");
		List<String> codes = new ArrayList<>();
		if (reasonCodes != null && reasonCodes.isArray()) {
			for (JsonNode code : reasonCodes) {
				codes.add(code.asString());
			}
		}
		return new AllocationResult(text(body, "outcome"), text(body, "agentId"), uuid(text(body, "decisionId")),
				text(body, "explanation"), List.copyOf(codes));
	}

	private AgentPresenceView presenceView(JsonNode body, String fallbackId) {
		String agentId = text(body, "id");
		return new AgentPresenceView((agentId != null) ? agentId : fallbackId,
				availability(text(body, "status")), instant(text(body, "lastHeartbeatAt")),
				!body.has("enabled") || body.get("enabled").asBoolean(true));
	}

	private HttpResult send(String method, String path, String role, String tenantId, String idempotencyKey,
			Object body) {
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(this.baseUrl.resolve(path))
				.timeout(this.timeout)
				.header("X-Tenant-Id", tenantId)
				.header("X-Role", role)
				.header("X-Correlation-Id", UUID.randomUUID().toString());
			if (idempotencyKey != null) {
				request.header("Idempotency-Key", idempotencyKey);
			}
			if (body != null) {
				request.header("Content-Type", "application/json")
					.method(method, HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(body)));
			}
			else {
				request.method(method, HttpRequest.BodyPublishers.noBody());
			}
			HttpResponse<String> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 500) {
				throw new AllocationUnavailableException("allocation answered HTTP " + response.statusCode(), null);
			}
			String text = response.body();
			JsonNode parsed = (text == null || text.isBlank()) ? null : this.json.readTree(text);
			return new HttpResult(response.statusCode(), parsed);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AllocationUnavailableException("allocation call interrupted", ex);
		}
		catch (AllocationUnavailableException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new AllocationUnavailableException("allocation unreachable: " + ex.getClass().getSimpleName(), ex);
		}
	}

	private void requireSuccess(HttpResult result) {
		if (result.status() >= 200 && result.status() < 300) {
			return;
		}
		String code = code(result.body());
		throw new AllocationRejectedException(result.status(), code);
	}

	private static String code(JsonNode body) {
		if (body == null) {
			return "UNKNOWN";
		}
		String code = text(body, "code");
		if (code == null) {
			code = text(body, "error");
		}
		return (code != null) ? code : "UNKNOWN";
	}

	private <T> T call(String operation, Supplier<T> action) {
		String outcome = "success";
		Instant started = Instant.now();
		try {
			return action.get();
		}
		catch (AllocationUnavailableException ex) {
			outcome = "unavailable";
			throw ex;
		}
		catch (AllocationRejectedException ex) {
			outcome = "rejected";
			throw ex;
		}
		catch (RuntimeException ex) {
			outcome = "error";
			throw ex;
		}
		finally {
			this.meters.timer("allocation.requests", "operation", operation, "outcome", outcome)
				.record(Duration.between(started, Instant.now()));
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asString();
	}

	private static UUID uuid(String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static Instant instant(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static Availability availability(String value) {
		if (value == null) {
			return Availability.OFFLINE;
		}
		try {
			return Availability.valueOf(value);
		}
		catch (IllegalArgumentException ex) {
			return Availability.OFFLINE;
		}
	}

	record HttpResult(int status, JsonNode body) {
	}

}
