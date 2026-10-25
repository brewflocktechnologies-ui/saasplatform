package com.byteflock.chatplatform.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class DependencyChecks {

	private static final Logger log = LoggerFactory.getLogger(DependencyChecks.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final JdbcClient jdbc;

	private final URI centrifugoHealthUrl;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	DependencyChecks(JdbcClient jdbc, @Value("${app.dependencies.centrifugo-health-url}") URI centrifugoHealthUrl) {
		this.jdbc = jdbc;
		this.centrifugoHealthUrl = centrifugoHealthUrl;
	}

	DependencyStatus database() {
		try {
			String version = this.jdbc
				.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
				.query(String.class)
				.optional()
				.orElse("none");
			return DependencyStatus.up("schema version " + version);
		}
		catch (RuntimeException ex) {
			log.warn("Database status check failed: {}", ex.getClass().getSimpleName());
			return DependencyStatus.down("database unreachable or not migrated");
		}
	}

	DependencyStatus centrifugo() {
		try {
			HttpRequest request = HttpRequest.newBuilder(this.centrifugoHealthUrl).timeout(TIMEOUT).GET().build();
			int code = this.http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			return (code == 200) ? DependencyStatus.up("health endpoint OK")
					: DependencyStatus.down("health endpoint returned " + code);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return DependencyStatus.down("check interrupted");
		}
		catch (Exception ex) {
			log.warn("Centrifugo status check failed: {}", ex.getClass().getSimpleName());
			return DependencyStatus.down("centrifugo unreachable");
		}
	}

}
