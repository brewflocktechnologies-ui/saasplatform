package com.byteflock.chatplatform.conversation.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the assignment worker properties (constructor-bound record). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssignmentProperties.class)
class AssignmentConfiguration {
}
