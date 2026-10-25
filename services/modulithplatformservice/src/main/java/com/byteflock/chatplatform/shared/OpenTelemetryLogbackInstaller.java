package com.byteflock.chatplatform.shared;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Connects the Logback OTEL appender (logback-spring.xml) to Spring Boot's OpenTelemetry SDK for OTLP log export. */
@Component
class OpenTelemetryLogbackInstaller implements InitializingBean {

	private final OpenTelemetry openTelemetry;

	OpenTelemetryLogbackInstaller(OpenTelemetry openTelemetry) {
		this.openTelemetry = openTelemetry;
	}

	@Override
	public void afterPropertiesSet() {
		OpenTelemetryAppender.install(this.openTelemetry);
	}

}
