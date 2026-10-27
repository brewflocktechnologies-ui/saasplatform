package com.byteflock.chatplatform.conversation;

/** Body of {@code POST /api/v1/agent/conversations/{id}/assign}; {@code expectedVersion} is optional. */
public record AssignRequest(AssignMode mode, Long expectedVersion) {
}
