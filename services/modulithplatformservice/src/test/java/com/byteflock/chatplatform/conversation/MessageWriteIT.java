package com.byteflock.chatplatform.conversation;

import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.conversation.internal.StateEventRepository;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * The durable write path (D4, D5, R-P24): one transaction per send, per-conversation ordering, idempotent replay and the
 * outbox rows that make realtime delivery possible.
 */
class MessageWriteIT extends PlatformIntegrationTest {

	@Autowired
	ConversationService conversations;

	@Autowired
	MessageService messages;

	@Autowired
	TenantDirectory directory;

	@MockitoSpyBean
	StateEventRepository stateEvents;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void contactSendPersistsMessageConversationAndOutboxAtomically() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		MessageService.SendResult result = send(fixture.contactCaller(), conversation, "client-1", "  hello  ");

		assertThat(result.created()).isTrue();
		assertThat(result.message().sequence()).isEqualTo(1);
		assertThat(result.message().body()).isEqualTo("hello");
		assertThat(this.data.lastSequence(conversation)).isEqualTo(1);
		assertThat(this.data.messageCount(conversation)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT last_message_preview FROM conversations WHERE id = :id")
			.param("id", conversation)
			.query(String.class)
			.single()).isEqualTo("hello");
		assertThat(this.data.outboxCount(conversation, "message.created")).isEqualTo(1);
		assertThat(this.data.outboxChannels(conversation, "message.created")).isEqualTo(
				"chat:conversation:" + conversation + ",chat:inbox:" + fixture.tenantId() + ":" + fixture.inbox().id());
		String payload = this.data.outboxPayloads(conversation, "message.created").get(0);
		assertThat(this.json.readTree(payload).at("/data/message/id").asString())
			.isEqualTo(result.message().id().toString());
	}

	@Test
	void rollbackLeavesNoPartialState() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.markPending(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		int outboxBefore = this.data.outboxCount(conversation, "conversation.updated");

		willThrow(new IllegalStateException("state event insert failed")).given(this.stateEvents)
			.insert(any(), any(), any(), any(), any(), any(), any(), any());

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> send(fixture.contactCaller(), conversation, "client-rollback", "lost"));
		assertThat(this.data.messageCount(conversation)).isZero();
		assertThat(this.data.lastSequence(conversation)).isZero();
		assertThat(this.data.status(conversation)).isEqualTo("PENDING");
		assertThat(this.data.outboxCount(conversation, "conversation.updated")).isEqualTo(outboxBefore);
		assertThat(this.data.outboxCount(conversation, "message.created")).isZero();
	}

	@Test
	void replayWithSameKeyAndBodyReturnsOriginal() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		MessageService.SendResult first = send(fixture.contactCaller(), conversation, "client-1", "hello");
		MessageService.SendResult replay = send(fixture.contactCaller(), conversation, "client-1", " hello ");

		assertThat(replay.created()).isFalse();
		assertThat(replay.message().id()).isEqualTo(first.message().id());
		assertThat(replay.message().sequence()).isEqualTo(1);
		assertThat(this.data.messageCount(conversation)).isEqualTo(1);
		assertThat(this.data.outboxCount(conversation, "message.created")).isEqualTo(1);
	}

	@Test
	void replayAfterArchiveReturnsOriginal() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		MessageService.SendResult first = send(fixture.contactCaller(), conversation, "client-1", "hello");
		this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, "done"));

		MessageService.SendResult replay = send(fixture.contactCaller(), conversation, "client-1", "hello");

		assertThat(replay.created()).isFalse();
		assertThat(replay.message().id()).isEqualTo(first.message().id());
		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> send(fixture.contactCaller(), conversation, "client-2", "again"))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CONVERSATION_ARCHIVED"));
	}

	@Test
	void replayAfterResolveReturnsOriginalForAgent() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		MessageService.SendResult first = send(fixture.agentCaller(), conversation, "agent-1", "on it");
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, "solved"));

		MessageService.SendResult replay = send(fixture.agentCaller(), conversation, "agent-1", "on it");

		assertThat(replay.created()).isFalse();
		assertThat(replay.message().id()).isEqualTo(first.message().id());
		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> send(fixture.agentCaller(), conversation, "agent-2", "one more"))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CONVERSATION_RESOLVED"));
	}

	@Test
	void reusedClientMessageIdWithDifferentBodyIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		send(fixture.contactCaller(), conversation, "client-1", "hello");

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> send(fixture.contactCaller(), conversation, "client-1", "different"))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CLIENT_MESSAGE_ID_REUSED"));
		assertThat(this.data.messageCount(conversation)).isEqualTo(1);
	}

	@Test
	void agentSendAddsParticipantRow() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		send(fixture.agentCaller(), conversation, "agent-1", "hello from the agent");

		assertThat(this.data.participantCount(conversation, "AGENT", fixture.agent().id())).isEqualTo(1);
	}

	@Test
	void contactMessageIntoResolvedReopens() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		long versionAfterResolve = this.data.version(conversation);
		this.data.setAssignment(conversation, "NONE", null, null);

		send(fixture.contactCaller(), conversation, "client-reopen", "I am back");

		assertThat(this.data.status(conversation)).isEqualTo("OPEN");
		assertThat(this.data.assignmentState(conversation)).isEqualTo("REOPEN_PENDING");
		assertThat(this.data.stateEventCount(conversation, "REOPENED_BY_CONTACT")).isEqualTo(1);
		assertThat(this.data.version(conversation)).isEqualTo(versionAfterResolve + 1);
		String updated = this.data.outboxPayloads(conversation, "conversation.updated").get(1);
		assertThat(this.json.readTree(updated).get("data").get("change").asString()).isEqualTo("REOPENED");
		assertThat(this.data.outboxCount(conversation, "message.created")).isEqualTo(1);
	}

	@Test
	void agentMessageIntoResolvedIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> send(fixture.agentCaller(), conversation, "agent-after-resolve", "hello"))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CONVERSATION_RESOLVED"));
	}

	@Test
	void anyMessageIntoArchivedIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, null));

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> send(fixture.contactCaller(), conversation, "client-archived", "hello"))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CONVERSATION_ARCHIVED"));
	}

	@Test
	void bodyIsTrimmedAndLimitedByCodePoints() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		String emoji = "\uD83D\uDE00";

		MessageService.SendResult maximal = send(fixture.contactCaller(), conversation, "max", emoji.repeat(4000));
		assertThat(maximal.created()).isTrue();
		assertThat(maximal.message().body()).hasSize(8000);

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> send(fixture.contactCaller(), conversation, "too-long", emoji.repeat(4001)));
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> send(fixture.contactCaller(), conversation, "blank", "   "));
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> send(fixture.contactCaller(), conversation, " ", "body"));
	}

	@Test
	void nonParticipantContactIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		var otherContact = this.directory.ensureContact(fixture.tenantId(), "other-" + UUID.randomUUID(), "Other");
		Caller other = new Caller(fixture.tenantId(), PrincipalType.CONTACT, otherContact.id().toString(),
				fixture.inbox().id());

		assertThatExceptionOfType(ConversationAccessDeniedException.class)
			.isThrownBy(() -> send(other, conversation, "client-other", "hello"));
	}

	@Test
	void crossTenantAgentIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ConversationTestData.Fixture otherTenant = this.data.seed();

		assertThatExceptionOfType(ConversationAccessDeniedException.class)
			.isThrownBy(() -> send(otherTenant.agentCaller(), conversation, "agent-other", "hello"));
	}

	private MessageService.SendResult send(Caller caller, UUID conversation, String clientMessageId, String body) {
		return this.messages.send(caller, conversation, new SendMessageRequest(clientMessageId, body));
	}

}
