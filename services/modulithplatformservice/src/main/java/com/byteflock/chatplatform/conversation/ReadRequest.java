package com.byteflock.chatplatform.conversation;

/** Body of {@code PUT …/read}: the highest sequence the caller has read (clamped to the conversation head). */
public record ReadRequest(Long lastReadSequence) {
}
