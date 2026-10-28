package com.byteflock.chatplatform.conversation;

/**
 * Reduced assignment view for contact callers and realtime conversation events: exactly {@code state} and
 * {@code agentId}, both always present ({@code agentId} is {@code null} when no agent holds the conversation).
 * The routing details ({@code decisionId}, {@code reason}, {@code explanation}) do not exist here on purpose
 * (Plan 01 ruling R-P29).
 */
public record ContactAssignmentView(AssignmentState state, String agentId) implements AssignmentInfo {
}
