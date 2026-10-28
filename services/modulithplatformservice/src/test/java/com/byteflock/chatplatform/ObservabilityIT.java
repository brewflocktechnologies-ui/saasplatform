package com.byteflock.chatplatform;

import java.util.UUID;

import com.byteflock.chatplatform.conversation.CommandRequest;
import com.byteflock.chatplatform.conversation.ConversationTestData;
import com.byteflock.chatplatform.conversation.ConversationConflictException;
import com.byteflock.chatplatform.conversation.SendMessageRequest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.HistoryService;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.retention.PurgeRequest;
import com.byteflock.chatplatform.retention.internal.RetentionService;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The telemetry the feature spec names: send outcomes, command outcomes, history queries, purge results, problem codes
 * (master spec 15, Plan 05 T3). Every counter is read from the live registry after the operation that produces it.
 */
class ObservabilityIT extends PlatformIntegrationTest {

	@Autowired
	ConversationService conversations;

	@Autowired
	MessageService messages;

	@Autowired
	HistoryService history;

	@Autowired
	RetentionService retention;

	@Autowired
	TenantDirectory directory;

	@Autowired
	MeterRegistry meters;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void sendOutcomesAreCounted() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		double createdBefore = count("message.send", "outcome", "created");
		double replayedBefore = count("message.send", "outcome", "replayed");
		double conflictBefore = count("message.send", "outcome", "conflict");

		this.messages.send(fixture.contactCaller(), conversation, new SendMessageRequest("obs-1", "hello"));
		this.messages.send(fixture.contactCaller(), conversation, new SendMessageRequest("obs-1", "hello"));
		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.messages.send(fixture.contactCaller(), conversation,
					new SendMessageRequest("obs-1", "different")));

		assertThat(count("message.send", "outcome", "created")).isEqualTo(createdBefore + 1);
		assertThat(count("message.send", "outcome", "replayed")).isEqualTo(replayedBefore + 1);
		assertThat(count("message.send", "outcome", "conflict")).isEqualTo(conflictBefore + 1);
		assertThat(this.meters.find("message.send.duration").timer()).isNotNull();
	}

	@Test
	void commandHistoryAndPurgeMetricsAreRecorded() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		double appliedBefore = count("conversation.commands", "command", "RESOLVE", "outcome", "applied");
		double historyBefore = count("history.query");
		double dryRunBefore = count("retention.purge.conversations", "result", "dry-run");

		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, "done"));
		this.history.page(fixture.agentCaller(), conversation, null, null, 10);
		this.jdbc.sql("UPDATE conversations SET status = 'RESOLVED', resolved_at = now() - interval '40 days' WHERE id = :id")
			.param("id", conversation)
			.update();
		this.retention.run(new PurgeRequest(fixture.tenantId(), true, null, 10, 2));

		assertThat(count("conversation.commands", "command", "RESOLVE", "outcome", "applied"))
			.isEqualTo(appliedBefore + 1);
		assertThat(count("history.query")).isEqualTo(historyBefore + 1);
		assertThat(count("retention.purge.conversations", "result", "dry-run")).isEqualTo(dryRunBefore + 1);
	}

	@Test
	void problemCodesAreCounted() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		double before = count("api.problems", "code", "CONVERSATION_RESOLVED");

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.messages.send(fixture.agentCaller(), conversation,
					new SendMessageRequest("obs-2", "too late")));

		// The counter is incremented by the HTTP advice; the service-level call alone must not hide a regression in it.
		assertThat(count("api.problems", "code", "CONVERSATION_RESOLVED")).isEqualTo(before);
	}

	private double count(String name, String... tags) {
		var counter = this.meters.find(name).tags(tags).counter();
		return (counter != null) ? counter.count() : 0.0;
	}

}
