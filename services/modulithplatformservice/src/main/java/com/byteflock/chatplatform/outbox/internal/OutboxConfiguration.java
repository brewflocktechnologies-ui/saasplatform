package com.byteflock.chatplatform.outbox.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the outbox worker: configuration properties and scheduling. Scheduling is enabled here so the application class
 * stays free of feature configuration; the {@link OutboxPublisher} bean always exists, and its tick returns immediately
 * while {@code app.outbox.enabled=false}, so tests drive {@code runOnce()} deterministically.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
class OutboxConfiguration {
}
