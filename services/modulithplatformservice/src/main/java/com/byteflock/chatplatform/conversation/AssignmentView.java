package com.byteflock.chatplatform.conversation;

import java.util.UUID;

/**
 * Full assignment view for agent callers (five keys; the routing details are {@code null} when unknown).
 *
 * @param decisionId allocation decision id, {@code null} for sweep assignments
 * @param reason machine-readable reason from the allocation service
 * @param explanation human-readable explanation shown in the dashboard
 */
public record AssignmentView(AssignmentState state, String agentId, UUID decisionId, String reason,
		String explanation) implements AssignmentInfo {
}
