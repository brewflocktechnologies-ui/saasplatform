package com.byteflock.chatplatform.tenancy;

import java.time.Instant;

/** A freshly issued session token and its expiry. The token is never logged. */
public record IssuedSession(String token, Instant expiresAt) {
}
