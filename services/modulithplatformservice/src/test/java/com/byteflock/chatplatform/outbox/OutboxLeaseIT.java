package com.byteflock.chatplatform.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.outbox.internal.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-row lease rule (Plan 01 ruling R-P28) with a short lease: the publisher stops a batch before a publication that
 * could outlive its claim, and the remaining rows are reclaimed only after the lease ends. A separate context overrides
 * the lease, which is why this is its own class.
 */
@TestPropertySource(properties = { "app.outbox.lease=10s", "app.outbox.batch-size=5" })
class OutboxLeaseIT extends PlatformIntegrationTest {

	@Autowired
	OutboxPublisher publisher;

	@Autowired
	OutboxAppender appender;

	@Autowired
	TransactionTemplate tx;

	@Test
	void stopsBatchWhenLeaseWouldExpire() {
		for (int index = 0; index < 5; index++) {
			append();
		}
		// Each broadcast advances the clock by 3 s; with a 10 s lease the fourth publication would end too late.
		CENTRIFUGO.onRequest(() -> this.testClock.advance(Duration.ofSeconds(3)));

		int processed = this.publisher.runOnce();

		assertThat(processed).isEqualTo(3);
		assertThat(CENTRIFUGO.requestsFor("/api/broadcast")).hasSize(3);
		assertThat(pending()).isEqualTo(2);
		// Their claim counted one attempt (as after a crash), but nothing was published for them yet.
		assertThat(attempts()).containsOnly(1);

		assertThat(this.publisher.runOnce()).isZero();

		this.testClock.advance(Duration.ofSeconds(2));
		this.publisher.runOnce();

		assertThat(CENTRIFUGO.requestsFor("/api/broadcast")).hasSize(5);
		assertThat(pending()).isZero();
	}

	private void append() {
		UUID aggregateId = UUID.randomUUID();
		this.tx.executeWithoutResult((status) -> this.appender.append("tenant-lease", "conversation",
				aggregateId.toString(), "message.created", List.of("chat:conversation:" + aggregateId),
				java.util.Map.of("message", java.util.Map.of("id", "m-1"))));
	}

	private int pending() {
		return this.jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'").query(Integer.class).single();
	}

	private List<Integer> attempts() {
		return this.jdbc.sql("SELECT attempts FROM outbox_events WHERE status = 'PENDING'")
			.query(Integer.class)
			.list();
	}

}
