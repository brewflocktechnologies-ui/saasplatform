package com.byteflock.chatplatform.retention;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.ConversationTestData;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.SendMessageRequest;
import com.byteflock.chatplatform.retention.internal.RetentionService;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Retention purge: dry run, deletion + archive/resolve, legal hold per batch, bounded batches (D16, R-P25, R-P31 M9). */
class RetentionIT extends PlatformIntegrationTest {

	@Autowired
	RetentionService retention;

	@Autowired
	ConversationService conversations;

	@Autowired
	MessageService messages;

	@Autowired
	TenantDirectory directory;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void dryRunReportsCountsAndChangesNothing() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = oldResolvedConversation(fixture, 2);

		PurgeRunView run = this.retention.run(new PurgeRequest(fixture.tenantId(), true, null, 50, 10));

		assertThat(run.status()).isEqualTo("COMPLETED");
		assertThat(run.dryRun()).isTrue();
		assertThat(run.conversationsExamined()).isEqualTo(1);
		assertThat(run.messagesDeleted()).isEqualTo(2);
		assertThat(this.data.messageCount(conversation)).isEqualTo(2);
		assertThat(this.purgedAt(conversation)).isNull();
	}

	@Test
	void purgeDeletesContentAndArchivesResolvedConversation() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = oldResolvedConversation(fixture, 2);
		int stateEvents = this.jdbc.sql("SELECT count(*) FROM conversation_state_events WHERE conversation_id = :id")
			.param("id", conversation)
			.query(Integer.class)
			.single();
		assertThat(this.data.outboxCount(conversation, "message.created")).isEqualTo(2);

		PurgeRunView run = this.retention.run(new PurgeRequest(fixture.tenantId(), false, null, 50, 10));

		assertThat(run.status()).isEqualTo("COMPLETED");
		assertThat(this.data.messageCount(conversation)).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM conversation_read_cursors WHERE conversation_id = :id")
			.param("id", conversation)
			.query(Integer.class)
			.single()).isZero();
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM outbox_events WHERE aggregate_type = 'conversation' AND aggregate_id = :id
				""").param("id", conversation.toString()).query(Integer.class).single()).isZero();
		assertThat(this.purgedAt(conversation)).isNotNull();
		assertThat(this.data.status(conversation)).isEqualTo("RESOLVED");
		assertThat(this.jdbc.sql("SELECT archived_at FROM conversations WHERE id = :id").param("id", conversation)
			.query(OffsetDateTime.class)
			.single()).isNotNull();
		assertThat(this.jdbc.sql("SELECT count(*) FROM conversation_state_events WHERE conversation_id = :id")
			.param("id", conversation)
			.query(Integer.class)
			.single()).isEqualTo(stateEvents);
	}

	@Test
	void legalHoldStopsThePurge() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = oldResolvedConversation(fixture, 2);
		this.retention.updatePolicy(fixture.tenantId(), 30, true);

		PurgeRunView run = this.retention.run(new PurgeRequest(fixture.tenantId(), false, null, 50, 10));

		assertThat(run.status()).isEqualTo("COMPLETED");
		assertThat(run.conversationsExamined()).isZero();
		assertThat(this.data.messageCount(conversation)).isEqualTo(2);
		assertThat(this.purgedAt(conversation)).isNull();
	}

	@Test
	void purgeIsBoundedByBatchAndMaxBatches() {
		ConversationTestData.Fixture fixture = this.data.seed();
		List<UUID> conversations = List.of(oldResolvedConversation(fixture, 1), oldResolvedConversation(fixture, 1),
				oldResolvedConversation(fixture, 1));

		PurgeRunView run = this.retention.run(new PurgeRequest(fixture.tenantId(), false, null, 1, 2));

		assertThat(run.batches()).isEqualTo(2);
		assertThat(conversations.stream().filter((id) -> purgedAt(id) != null).count()).isEqualTo(2);
	}

	private UUID oldResolvedConversation(ConversationTestData.Fixture fixture, int messageCount) {
		var contact = this.directory.ensureContact(fixture.tenantId(), "retention-" + UUID.randomUUID(), "Old");
		var caller = new com.byteflock.chatplatform.tenancy.Caller(fixture.tenantId(),
				com.byteflock.chatplatform.tenancy.PrincipalType.CONTACT, contact.id().toString(),
				fixture.inbox().id());
		UUID conversation = this.conversations.startOrResume(caller).conversation().id();
		for (int index = 1; index <= messageCount; index++) {
			this.messages.send(caller, conversation, new SendMessageRequest("retention-" + index,
					"old message " + index));
		}
		this.jdbc.sql("""
				UPDATE conversations SET status = 'RESOLVED', resolved_at = now() - interval '40 days' WHERE id = :id
				""").param("id", conversation).update();
		return conversation;
	}

	private OffsetDateTime purgedAt(UUID conversation) {
		return this.jdbc.sql("SELECT purged_at FROM conversations WHERE id = :id").param("id", conversation)
			.query(OffsetDateTime.class)
			.optional()
			.orElse(null);
	}

}
