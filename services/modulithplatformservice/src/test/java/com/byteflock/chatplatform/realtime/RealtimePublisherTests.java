package com.byteflock.chatplatform.realtime;

import java.net.URI;
import java.util.Map;

import com.byteflock.chatplatform.CentrifugoStub;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

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
	void postsChannelAndDataToServerApiWithApiKey() {
		boolean ok = this.publisher.publish("chat:conversation:abc", Map.of("type", "message.created"));

		assertThat(ok).isTrue();
		CentrifugoStub.Request request = this.stub.requests.getFirst();
		assertThat(request.path()).isEqualTo("/api/publish");
		assertThat(request.apiKey()).isEqualTo("the-api-key");
		JsonNode body = this.json.readTree(request.body());
		assertThat(body.get("channel").asString()).isEqualTo("chat:conversation:abc");
		assertThat(body.get("data").get("type").asString()).isEqualTo("message.created");
		assertThat(this.meters.counter("chat.realtime.publications", "outcome", "success").count()).isEqualTo(1);
	}

	@Test
	void reportsFailureForCentrifugoErrorReply() {
		this.stub.respond(200, "{\"error\":{\"code\":102,\"message\":\"unknown channel\"}}");
		assertThat(this.publisher.publish("chat:inbox", Map.of())).isFalse();
		assertThat(this.meters.counter("chat.realtime.publications", "outcome", "failure").count()).isEqualTo(1);
	}

	@Test
	void reportsFailureForHttpErrorAndUnreachableServer() {
		this.stub.respond(401, "");
		assertThat(this.publisher.publish("chat:inbox", Map.of())).isFalse();

		RealtimePublisher unreachable = new RealtimePublisher(URI.create("http://127.0.0.1:9"), "k", this.json,
				this.meters);
		assertThat(unreachable.publish("chat:inbox", Map.of())).isFalse();
	}

}
