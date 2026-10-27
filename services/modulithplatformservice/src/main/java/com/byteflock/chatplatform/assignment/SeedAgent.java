package com.byteflock.chatplatform.assignment;

/**
 * One agent to seed into the allocation service.
 *
 * @param id allocation agent id (equals the platform agent id)
 * @param displayName display name
 * @param maxActiveAssignments capacity; {@code null} means unlimited, 0 accepts nothing
 */
public record SeedAgent(String id, String displayName, int maxActiveAssignments) {
}
