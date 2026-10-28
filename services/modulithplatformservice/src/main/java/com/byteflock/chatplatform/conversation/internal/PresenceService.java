package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.byteflock.chatplatform.assignment.AgentPresenceView;
import com.byteflock.chatplatform.assignment.AllocationClient;
import com.byteflock.chatplatform.assignment.Availability;
import com.byteflock.chatplatform.conversation.ConversationNotFoundException;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.TenantDirectory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Agent presence goes through the allocation service (D9): the platform forwards the choice and reads raw availability,
 * never storing a second copy. {@code agentOnline} is computed here from a 5 s per-tenant cache and
 * {@code app.presence.stale-after}; it is {@code null} when the allocation service is unavailable (unknown, not false).
 */
@Service
public class PresenceService {

	private static final Duration CACHE_TTL = Duration.ofSeconds(5);

	private final AllocationClient client;

	private final TenantDirectory directory;

	private final Clock clock;

	private final Duration staleAfter;

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	PresenceService(AllocationClient client, TenantDirectory directory, Clock clock,
			@Value("${app.presence.stale-after}") Duration staleAfter) {
		this.client = client;
		this.directory = directory;
		this.clock = clock;
		this.staleAfter = staleAfter;
	}

	public AgentPresenceView current(Caller caller) {
		return agents(caller.tenantId()).stream()
			.filter((agent) -> agent.agentId().equals(caller.principalId()))
			.findFirst()
			.orElseThrow(ConversationNotFoundException::new);
	}

	public AgentPresenceView update(Caller caller, Availability availability) {
		AgentPresenceView view = this.client.presence(caller.tenantId(), caller.principalId(), availability);
		this.cache.remove(caller.tenantId());
		return view;
	}

	/** {@code true}/{@code false} when known, {@code null} when the allocation service cannot be reached. */
	public Boolean agentOnline(String tenantId, String assignedAgentId) {
		List<AgentPresenceView> agents;
		try {
			agents = agents(tenantId);
		}
		catch (RuntimeException ex) {
			return null;
		}
		if (assignedAgentId != null) {
			return agents.stream()
				.filter((agent) -> agent.agentId().equals(assignedAgentId))
				.findFirst()
				.map(this::online)
				.orElse(Boolean.FALSE);
		}
		return agents.stream().anyMatch(this::online);
	}

	/** Display name for the agent view, from the platform roster (the engine does not store a display name). */
	public String displayName(Caller caller) {
		return this.directory.agent(caller.tenantId(), caller.principalId())
			.map((agent) -> agent.displayName())
			.orElse(caller.principalId());
	}

	private List<AgentPresenceView> agents(String tenantId) {
		Instant now = this.clock.instant();
		Cached cached = this.cache.get(tenantId);
		if (cached != null && cached.at().plus(CACHE_TTL).isAfter(now)) {
			return cached.agents();
		}
		List<AgentPresenceView> agents = this.client.agents(tenantId);
		this.cache.put(tenantId, new Cached(agents, now));
		return agents;
	}

	private boolean online(AgentPresenceView agent) {
		if (!agent.enabled() || agent.availability() != Availability.ONLINE || agent.lastHeartbeatAt() == null) {
			return false;
		}
		return agent.lastHeartbeatAt().plus(this.staleAfter).isAfter(this.clock.instant());
	}

	private record Cached(List<AgentPresenceView> agents, Instant at) {
	}

}
