package com.byteflock.chatplatform.realtime;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.byteflock.chatplatform.CentrifugoStub;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Centrifugo server API: single-channel publish keeps working, broadcast posts the idempotency key and classifies every
 * documented failure into transport (retry) versus rejection (park) — Plan 01 ruling R-P30.
 */
class RealtimePublisherTests {

	final CentrifugoStub stub = new CentrifugoStub();

	final JsonMapper json = JsonMapper.builder().build();

	final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	final RealtimePublisher publisher = new RealtimePublisher(URI.create(this.stub.url()), "the-api-key", this.json,
			this.meters);

	@AfterEach
	void stop() {
		this.stub.close();
	}

	@Test
	void publishStillWorks() {
		boolean ok = this.publisher.publish("chat:conversation:abc", Map.of("type", "message.created"));

		assertThat(ok).isTrue();
		CentrifugoStub.Request request = this.stub.requests.getFirst();
		assertThat(request.path()).isEqualTo("/api/publish");
		assertThat(request.apiKey()).isEqualTo("the-api-key");
		JsonNode body = this.json.readTree(request.body());
		assertThat(body.get("channel").asString()).isEqualTo("chat:conversation:abc");
		assertThat(body.get("data").get("type").asString()).isEqualTo("message.created");
		assertThat(this.meters.counter("chat.realtime.publications", "outcome", "success", "kind", "publish").count())
			.isEqualTo(1);
	}

	@Test
	void broadcastPostsChannelsDataAndIdempotencyKey() {
		BroadcastResult result = this.publisher.broadcast(
				List.of("chat:conversation:abc", "chat:inbox:tenant-a:inbox"),
				Map.of("eventType", "message.created"), "event-1");

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.PUBLISHED);
		CentrifugoStub.Request request = this.stub.requestsFor("/api/broadcast").getFirst();
		assertThat(request.apiKey()).isEqualTo("the-api-key");
		JsonNode body = this.json.readTree(request.body());
		assertThat(body.get("channels").size()).isEqualTo(2);
		assertThat(body.get("channels").get(1).asString()).isEqualTo("chat:inbox:tenant-a:inbox");
		assertThat(body.get("idempotency_key").asString()).isEqualTo("event-1");
		assertThat(body.get("data").get("eventType").asString()).isEqualTo("message.created");
		assertThat(this.meters.counter("chat.realtime.publications", "outcome", "success", "kind", "broadcast").count())
			.isEqualTo(1);
	}

	@Test
	void broadcastErrorObjectIsRejected() {
		this.stub.respondForPath("/api/broadcast", 200,
				"{\"error\":{\"code\":103,\"message\":\"permission denied\"}}");

		BroadcastResult result = broadcast();

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.REJECTED);
		assertThat(result.error()).isEqualTo("103");
	}

	@Test
	void broadcastTemporaryErrorObjectIsTransportFailure() {
		this.stub.respondForPath("/api/broadcast", 200,
				"{\"error\":{\"code\":100,\"message\":\"internal\",\"temporary\":true}}");

		BroadcastResult result = broadcast();

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.TRANSPORT_FAILURE);
		assertThat(result.error()).isEqualTo("100");
	}

	@Test
	void broadcastHttp500IsTransportFailure() {
		this.stub.respondForPath("/api/broadcast", 500, "{}");

		BroadcastResult result = broadcast();

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.TRANSPORT_FAILURE);
		assertThat(result.error()).isEqualTo("HTTP 500");
	}

	@Test
	void broadcastHttp4xxIsRejected() {
		this.stub.respondForPath("/api/broadcast", 401, "{}");

		BroadcastResult result = broadcast();

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.REJECTED);
		assertThat(result.error()).isEqualTo("HTTP 401");
	}

	@Test
	void broadcastAnyChannelErrorIsRejected() {
		this.stub.respondForPath("/api/broadcast", 200,
				"{\"result\":{\"responses\":[{\"result\":{}},{\"error\":{\"code\":102,\"message\":\"unknown channel\"}}]}}");

		BroadcastResult result = broadcast();

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.REJECTED);
		assertThat(result.error()).isEqualTo("102");
	}

	@Test
	void broadcastUnreachableIsTransportFailure() {
		RealtimePublisher unreachable = new RealtimePublisher(URI.create("http://127.0.0.1:9"), "k", this.json,
				this.meters);

		Instant start = Instant.now();
		BroadcastResult result = unreachable.broadcast(List.of("chat:inbox:a:b"), Map.of(), "event-2");

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.TRANSPORT_FAILURE);
		assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void broadcastTimeoutIsTransportFailure() {
		this.stub.delayNext(Duration.ofSeconds(3));

		Instant start = Instant.now();
		BroadcastResult result = broadcast();
		Duration elapsed = Duration.between(start, Instant.now());

		assertThat(result.kind()).isEqualTo(BroadcastResult.Kind.TRANSPORT_FAILURE);
		assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
	}

	private BroadcastResult broadcast() {
		return this.publisher.broadcast(List.of("chat:conversation:abc"), Map.of("eventType", "message.created"),
				"event-1");
	}

}
