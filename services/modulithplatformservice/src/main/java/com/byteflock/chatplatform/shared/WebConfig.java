package com.byteflock.chatplatform.shared;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Explicit CORS allow-list for the local dashboard and widget origins (APP_CORS_ALLOWED_ORIGINS). */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

	private final List<String> allowedOrigins;

	WebConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
			.allowedOrigins(this.allowedOrigins.toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
			.allowedHeaders("Authorization", "Content-Type", "Idempotency-Key", "X-Admin-Token")
			.exposedHeaders(TraceIdResponseFilter.HEADER);
	}

}
