package com.byteflock.chatplatform.conversation.internal;

import java.time.Instant;

import com.byteflock.chatplatform.assignment.AgentPresenceView;
import com.byteflock.chatplatform.assignment.Availability;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Agent presence (D9): the dashboard reads and forwards its availability; the allocation service owns the state. */
@RestController
class AgentPresenceController {

	private final PresenceService presence;

	AgentPresenceController(PresenceService presence) {
		this.presence = presence;
	}

	@GetMapping("/api/v1/agent/me")
	@RequiresPrincipal(PrincipalType.AGENT)
	AgentMeView me(Caller caller) {
		return view(caller, this.presence.current(caller));
	}

	@PutMapping("/api/v1/agent/presence")
	@RequiresPrincipal(PrincipalType.AGENT)
	AgentMeView update(Caller caller, @RequestBody(required = false) PresenceRequest request) {
		Availability availability = (request != null && request.availability() != null) ? request.availability()
				: Availability.OFFLINE;
		return view(caller, this.presence.update(caller, availability));
	}

	private AgentMeView view(Caller caller, AgentPresenceView presence) {
		return new AgentMeView(caller.principalId(), caller.tenantId(), this.presence.displayName(caller),
				presence.availability(), presence.lastHeartbeatAt());
	}

	record PresenceRequest(Availability availability) {
	}

	record AgentMeView(String agentId, String tenantId, String displayName, Availability availability,
			Instant lastHeartbeatAt) {
	}

}
