package com.byteflock.chatplatform.tenancy;

import java.time.Instant;

/**
 * Result of {@link SessionTokens#verifyForRefresh(String)}: the contact caller plus the original {@code auth_time}.
 * The refresh path carries the first issue time so refreshing a session never extends its absolute lifetime
 * (Plan 01 ruling R-P31 M4; final-review N2).
 */
public record RefreshableContact(Caller caller, Instant authTime) {
}
