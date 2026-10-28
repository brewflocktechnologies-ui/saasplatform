package com.byteflock.chatplatform.assignment;

import java.util.List;
import java.util.UUID;

/**
 * One allocation decision. Every outcome (including {@code QUEUED_*} and {@code REJECTED_INVALID_INPUT}) is answered with
 * HTTP 200 by the allocation service, so callers must branch on {@link #outcome()}, never on the HTTP status.
 *
 * @param outcome engine outcome name
 * @param agentId assigned agent, {@code null} unless the outcome assigned one
 * @param decisionId decision record id; {@code null} for a sweep assignment observed via {@code findConversation}
 * @param explanation human-readable explanation (agents only, never sent to visitors)
 * @param reasonCodes engine reason codes
 */
public record AllocationResult(String outcome, String agentId, UUID decisionId, String explanation,
		List<String> reasonCodes) {

	public boolean assigned() {
		return "ASSIGNED".equals(this.outcome);
	}

	public boolean queued() {
		return this.outcome != null && this.outcome.startsWith("QUEUED_");
	}

}
