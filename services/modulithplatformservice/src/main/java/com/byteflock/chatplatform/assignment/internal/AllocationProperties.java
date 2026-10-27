package com.byteflock.chatplatform.assignment.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Allocation service connection ({@code app.allocation.*}).
 *
 * @param url base URL (host mode {@code http://localhost:8081}, containers {@code http://allocation-service:8080})
 * @param timeout connect and read timeout of every call; also the lease headroom the workers reserve (R-P28)
 */
@ConfigurationProperties("app.allocation")
public record AllocationProperties(String url, Duration timeout) {
}
