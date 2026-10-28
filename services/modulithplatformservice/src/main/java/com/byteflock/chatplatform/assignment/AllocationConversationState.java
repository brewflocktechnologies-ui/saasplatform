package com.byteflock.chatplatform.assignment;

/** The allocation service's view of a conversation: its status and the agent holding it. */
public record AllocationConversationState(String status, String assignedAgentId) {

	public boolean assigned() {
		return "ASSIGNED".equals(this.status) || "ACCEPTED".equals(this.status) || "ACTIVE".equals(this.status);
	}

}
