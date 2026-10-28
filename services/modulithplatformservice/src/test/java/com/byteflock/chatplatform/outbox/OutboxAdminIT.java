package com.byteflock.chatplatform.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.outbox.internal.OutboxProperties;
import com.byteflock.chatplatform.outbox.internal.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Local-dev outbox admin API: token-protected listing and replay of parked events. */
class OutboxAdminIT extends PlatformIntegrationTest {

	@Autowired
	OutboxAppender appender;

	@Autowired
	OutboxPublisher publisher;

	@Autowired
	OutboxProperties properties;

	@Autowired
	TransactionTemplate tx;

	@Test
	void listRequiresAdminToken() {
		assertThat(this.mvc.get().uri("/api/v1/admin/outbox").exchange()).hasStatus(401);
		assertThat(this.mvc.get().uri("/api/v1/admin/outbox").header("X-Admin-Token", "wrong").exchange())
			.hasStatus(401);
	}

	@Test
	void listsParkedEvents() {
		UUID parked = parkOneEvent();

		var result = this.mvc.get()
			.uri("/api/v1/admin/outbox?status=PARKED")
			.header("X-Admin-Token", ADMIN_TOKEN)
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(body(result).size()).isEqualTo(1);
		assertThat(body(result).get(0).get("eventId").asString()).isEqualTo(parked.toString());
		assertThat(body(result).get(0).get("status").asString()).isEqualTo("PARKED");
	}

	@Test
	void replayResetsParkedEvent() {
		UUID parked = parkOneEvent();

		var result = this.mvc.post()
			.uri("/api/v1/admin/outbox/" + parked + "/replay")
			.header("X-Admin-Token", ADMIN_TOKEN)
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(body(result).get("status").asString()).isEqualTo("PENDING");
		assertThat(body(result).get("attempts").asInt()).isZero();
	}

	@Test
	void replayUnknownIs404() {
		assertThat(this.mvc.post()
			.uri("/api/v1/admin/outbox/" + UUID.randomUUID() + "/replay")
			.header("X-Admin-Token", ADMIN_TOKEN)
			.exchange()).hasStatus(404);
	}

	private UUID parkOneEvent() {
		UUID aggregateId = UUID.randomUUID();
		UUID[] holder = new UUID[1];
		this.tx.executeWithoutResult((status) -> holder[0] = this.appender.append("tenant-admin", "conversation",
				aggregateId.toString(), "message.created", List.of("chat:conversation:" + aggregateId),
				Map.of("message", Map.of("id", "m-1"))));
		CENTRIFUGO.respondForPath("/api/broadcast", 200, "{\"error\":{\"code\":102,\"message\":\"unknown channel\"}}");
		for (int attempt = 0; attempt < this.properties.maxAttempts(); attempt++) {
			this.publisher.runOnce();
			this.testClock.advance(Duration.ofSeconds(61));
		}
		assertThat(this.jdbc.sql("SELECT status FROM outbox_events WHERE event_id = :id").param("id", holder[0])
			.query(String.class)
			.single()).isEqualTo("PARKED");
		return holder[0];
	}

}
