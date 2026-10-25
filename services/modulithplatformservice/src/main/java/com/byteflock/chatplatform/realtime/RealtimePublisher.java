package com.byteflock.chatplatform.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes to Centrifugo through its server HTTP API ({@code POST /api/publish}, {@code X-API-Key}).
 * Never throws: returns {@code false} on failure so callers can report "persisted but not delivered live".
 * No exactly-once guarantee (no outbox yet) — see docs/features/local-chat-mvp.md.
 */
@Component
public class RealtimePublisher {

	private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final URI publishUri;

	private final String apiKey;

	private final JsonMapper json;

	private final MeterRegistry meters;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	RealtimePublisher(@Value("${app.centrifugo.url}") URI baseUrl, @Value("${app.centrifugo.api-key}") String apiKey,
			JsonMapper json, MeterRegistry meters) {
		this.publishUri = baseUrl.resolve("/api/publish");
		this.apiKey = apiKey;
		this.json = json;
		this.meters = meters;
	}

	/** @return true when Centrifugo accepted the publication */
	public boolean publish(String channel, Object data) {
		boolean ok = send(channel, data);
		this.meters.counter("chat.realtime.publications", "outcome", ok ? "success" : "failure").increment();
		return ok;
	}

	private boolean send(String channel, Object data) {
		try {
			String body = this.json.writeValueAsString(Map.of("channel", channel, "data", data));
			HttpRequest request = HttpRequest.newBuilder(this.publishUri)
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("X-API-Key", this.apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("Centrifugo publish to channel={} failed: HTTP {}", channel, response.statusCode());
				return false;
			}
			JsonNode error = this.json.readTree(response.body()).get("error");
			if (error != null && !error.isNull()) {
				log.warn("Centrifugo publish to channel={} rejected: code={}", channel, error.path("code").asInt());
				return false;
			}
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.warn("Centrifugo publish to channel={} interrupted", channel);
			return false;
		}
		catch (Exception ex) {
			log.warn("Centrifugo publish to channel={} failed: {}", channel, ex.getClass().getSimpleName());
			return false;
		}
	}

}
