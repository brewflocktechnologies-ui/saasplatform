package com.byteflock.chatplatform.realtime;

import java.time.Instant;

public record IssuedToken(String token, Instant expiresAt) {
}
