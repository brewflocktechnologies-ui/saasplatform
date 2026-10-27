package com.byteflock.chatplatform.assignment.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the allocation connection properties without annotating the record itself (a record is constructor-bound). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AllocationProperties.class)
class AssignmentConfiguration {
}