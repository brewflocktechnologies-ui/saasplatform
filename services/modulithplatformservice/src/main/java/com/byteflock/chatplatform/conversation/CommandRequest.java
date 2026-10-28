package com.byteflock.chatplatform.conversation;

/**
 * Body of a lifecycle command. Both fields are optional: {@code expectedVersion} makes the command optimistic, and
 * {@code reason} is stored in the state event (and as {@code archive_reason} for an archive).
 */
public record CommandRequest(Long expectedVersion, String reason) {
}
