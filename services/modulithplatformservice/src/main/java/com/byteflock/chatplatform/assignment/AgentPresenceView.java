package com.byteflock.chatplatform.assignment;

import java.time.Instant;

/** One agent as the allocation service reports it (raw availability, never an "effective" status — the platform ages it). */
public record AgentPresenceView(String agentId, Availability availability, Instant lastHeartbeatAt, boolean enabled) {
}
