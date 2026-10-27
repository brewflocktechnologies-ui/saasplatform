package com.byteflock.chatplatform.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes to Centrifugo through its server HTTP API ({@code X-API-Key}): {@code POST /api/publish} for one channel and
 * {@code POST /api/broadcast} for the outbox (one request per event, many channels, `idempotency_key` = eventId).
 * <p>
 * Never throws. {@link #publish} keeps its boolean contract for the demo module; {@link #broadcast} classifies failures
 * so the outbox can retry transport problems and park genuine rejections (Plan 01 ruling R-P30).
 */
@Component
public class RealtimePublisher {

	private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final URI publishUri;

	private final URI broadcastUri;

	private final String apiKey;

	private final JsonMapper json;

	private final MeterRegistry meters;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	RealtimePublisher(@Value("${app.centrifugo.url}") URI baseUrl, @Value("${app.centrifugo.api-key}") String apiKey,
			JsonMapper json, MeterRegistry meters) {
		this.publishUri = baseUrl.resolve("/api/publish");
		this.broadcastUri = baseUrl.resolve("/api/broadcast");
		this.apiKey = apiKey;
		this.json = json;
		this.meters = meters;
	}

	/** @return true when Centrifugo accepted the publication */
	public boolean publish(String channel, Object data) {
		boolean ok = send(this.publishUri, Map.of("channel", channel, "data", data)).isPublished();
		this.meters.counter("chat.realtime.publications", "outcome", ok ? "success" : "failure", "kind", "publish")
			.increment();
		return ok;
	}

	/**
	 * One publication to several channels with a deduplication key. A broadcast is published only when the HTTP status is
	 * 200, there is no top-level `error` and no per-channel `error` in `result.responses`.
	 */
	public BroadcastResult broadcast(List<String> channels, Object data, String idempotencyKey) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("channels", channels);
		body.put("data", data);
		body.put("idempotency_key", idempotencyKey);
		BroadcastResult result = send(this.broadcastUri, body);
		this.meters
			.counter("chat.realtime.publications", "outcome", result.isPublished() ? "success" : "failure", "kind",
					"broadcast")
			.increment();
		return result;
	}

	private BroadcastResult send(URI uri, Map<String, Object> payload) {
		try {
			String body = this.json.writeValueAsString(payload);
			HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("X-API-Key", this.apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status >= 500) {
				log.warn("Centrifugo server API {} failed with HTTP {}", uri.getPath(), status);
				return BroadcastResult.transportFailure("HTTP " + status);
			}
			if (status != 200) {
				log.warn("Centrifugo server API {} rejected with HTTP {}", uri.getPath(), status);
				return BroadcastResult.rejected("HTTP " + status);
			}
			JsonNode tree = this.json.readTree(response.body());
			BroadcastResult failure = classifyError(tree.get("error"));
			if (failure != null) {
				return failure;
			}
			JsonNode responses = tree.path("result").path("responses");
			if (responses.isArray()) {
				for (JsonNode channelResponse : responses) {
					BroadcastResult channelFailure = classifyError(channelResponse.get("error"));
					if (channelFailure != null) {
						return channelFailure;
					}
				}
			}
			return BroadcastResult.published();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.warn("Centrifugo server API {} interrupted", uri.getPath());
			return BroadcastResult.transportFailure("InterruptedException");
		}
		catch (Exception ex) {
			log.warn("Centrifugo server API {} unreachable: {}", uri.getPath(), ex.getClass().getSimpleName());
			return BroadcastResult.transportFailure(ex.getClass().getSimpleName());
		}
	}

	/** Classifies a Centrifugo error object; returns {@code null} when there is none. */
	private BroadcastResult classifyError(JsonNode error) {
		if (error == null || error.isNull() || error.isMissingNode()) {
			return null;
		}
		boolean temporary = error.path("temporary").asBoolean(false);
		String code = error.path("code").isMissingNode() ? "unknown" : error.path("code").asString();
		if (temporary) {
			return BroadcastResult.transportFailure(code);
		}
		return BroadcastResult.rejected(code);
	}

}
