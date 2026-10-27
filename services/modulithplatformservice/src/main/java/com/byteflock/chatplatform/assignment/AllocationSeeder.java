package com.byteflock.chatplatform.assignment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Seeds the allocation service with a tenant, its queue, its agents and a published FIFO + round-robin policy (ADR-0009).
 * <p>
 * Every step is an idempotent check-then-create: the engine's {@code POST /agents} is a full replace that would clobber
 * live presence, so an existing agent is never re-posted, and an existing draft policy is only published.
 */
@Component
public class AllocationSeeder {

	static final String POLICY_ID = "local-round-robin";

	private final AllocationClient client;

	public AllocationSeeder(AllocationClient client) {
		this.client = client;
	}

	public void ensureTenant(String tenantId, String tenantName, String queueId, List<SeedAgent> agents) {
		createIfAccepted(this.client.adminPost(tenantId, "/api/v1/tenants", Map.of("id", tenantId, "name", tenantName)));
		ensureQueue(tenantId, queueId);
		for (SeedAgent agent : agents) {
			ensureAgent(tenantId, queueId, agent);
		}
		ensurePolicy(tenantId, queueId);
	}

	private void ensureQueue(String tenantId, String queueId) {
		JsonNode queues = this.client.operatorGet(tenantId, "/api/v1/queues").body();
		if (queues != null && queues.isArray()) {
			for (JsonNode queue : queues) {
				if (queueId.equals(text(queue, "id"))) {
					return;
				}
			}
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", queueId);
		body.put("name", "Support");
		body.put("description", "Local development queue");
		createIfAccepted(this.client.adminPost(tenantId, "/api/v1/queues", body));
	}

	private void ensureAgent(String tenantId, String queueId, SeedAgent agent) {
		AllocationClient.HttpResult existing = this.client.adminGet(tenantId, "/api/v1/agents/" + agent.id());
		if (existing.status() / 100 == 2) {
			// Never re-post: POST /agents is a full replace and would reset status, memberships and presence.
			return;
		}
		Map<String, Object> membership = new LinkedHashMap<>();
		membership.put("queueId", queueId);
		membership.put("maxActiveInQueue", null);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", agent.id());
		body.put("displayName", agent.displayName());
		body.put("maxActiveAssignments", agent.maxActiveAssignments());
		body.put("status", Availability.OFFLINE.name());
		body.put("enabled", true);
		body.put("memberships", List.of(membership));
		createIfAccepted(this.client.adminPost(tenantId, "/api/v1/agents", body));
	}

	private void ensurePolicy(String tenantId, String queueId) {
		AllocationClient.HttpResult existing = this.client.adminGet(tenantId, "/api/v1/policies/" + POLICY_ID);
		if (existing.status() == 404) {
			createIfAccepted(this.client.adminPost(tenantId, "/api/v1/policies", policyDefinition(queueId)));
		}
		else if (existing.status() / 100 == 2) {
			requireSuccess(existing);
			if ("PUBLISHED".equals(text(existing.body(), "state"))) {
				return;
			}
		}
		AllocationClient.HttpResult published = this.client.adminPost(tenantId,
				"/api/v1/policies/" + POLICY_ID + "/publish", null);
		if (published.status() / 100 != 2) {
			requireSuccess(published);
		}
	}

	/** The full policy definition: strategies are objects, {@code maxTraceCandidates} is required (validation). */
	static Map<String, Object> policyDefinition(String queueId) {
		Map<String, Object> policy = new LinkedHashMap<>();
		policy.put("policyId", POLICY_ID);
		policy.put("name", "Local round-robin");
		policy.put("enabled", true);
		policy.put("queueIds", List.of(queueId));
		policy.put("mode", "AUTO");
		policy.put("conversationSelection", nameOnly("fifo"));
		List<Map<String, Object>> filters = new ArrayList<>();
		filters.add(nameOnly("tenant"));
		filters.add(nameOnly("agent-enabled"));
		filters.add(nameOnly("queue-membership"));
		filters.add(Map.of("name", "availability", "params", Map.of("heartbeatTtlSeconds", 60)));
		filters.add(nameOnly("capacity"));
		filters.add(nameOnly("excluded-agents"));
		policy.put("eligibility", Map.of("filters", filters));
		policy.put("agentRanking", nameOnly("round-robin"));
		policy.put("tieBreak", "AGENT_ID");
		policy.put("capacity", Map.of("strategy", "count-based", "defaultMaxActive", 3,
				"countedStates", List.of("ASSIGNED", "ACCEPTED", "ACTIVE")));
		policy.put("fallback", nameOnly("keep-queued"));
		policy.put("maxTraceCandidates", 200);
		return policy;
	}

	private static Map<String, Object> nameOnly(String name) {
		Map<String, Object> strategy = new LinkedHashMap<>();
		strategy.put("name", name);
		return strategy;
	}

	private static void createIfAccepted(AllocationClient.HttpResult result) {
		if (result.status() / 100 == 2 || result.status() == 409) {
			return;
		}
		requireSuccess(result);
	}

	private static void requireSuccess(AllocationClient.HttpResult result) {
		if (result.status() / 100 != 2) {
			throw new AllocationRejectedException(result.status(), "SEED_FAILED");
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = (node != null) ? node.get(field) : null;
		return (value == null || value.isNull()) ? null : value.asString();
	}

}
