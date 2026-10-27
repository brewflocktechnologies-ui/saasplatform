package com.byteflock.chatplatform.conversation.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the assignment worker properties (constructor-bound record; class name kept unique across modules). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssignmentProperties.class)
class ConversationAssignmentConfiguration {
}