package com.byteflock.chatplatform.conversation;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.conversation.internal.ReadCursorService;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Read cursors (D13, R12, R-P26): monotonic, clamped, per participant, and never deadlocking with a first send. */
class ReadCursorIT extends PlatformIntegrationTest {

	@Autowired
	ConversationService conversations;

	@Autowired
	MessageService messages;

	@Autowired
	ReadCursorService readCursors;

	@Autowired
	TenantDirectory directory;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void advanceIsMonotonic() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		seedAgentMessages(fixture, conversation, 10);

		this.readCursors.advance(fixture.contactCaller(), conversation, 10);
		this.readCursors.advance(fixture.contactCaller(), conversation, 5);

		assertThat(this.data.cursor(conversation, "CONTACT", fixture.contact().id().toString())).isEqualTo(10);
	}

	@Test
	void unreadDerivedFromCursor() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		seedAgentMessages(fixture, conversation, 12);

		this.readCursors.advance(fixture.contactCaller(), conversation, 10);
		assertThat(this.conversations.get(fixture.contactCaller(), conversation).unreadCount()).isEqualTo(2);

		this.readCursors.advance(fixture.contactCaller(), conversation, 12);
		assertThat(this.conversations.get(fixture.contactCaller(), conversation).unreadCount()).isZero();
		assertThat(this.conversations.get(fixture.agentCaller(), conversation).unreadCount()).isZero();
	}

	@Test
	void cursorCannotExceedLastSequence() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		seedAgentMessages(fixture, conversation, 12);

		this.readCursors.advance(fixture.contactCaller(), conversation, 99);

		assertThat(this.data.cursor(conversation, "CONTACT", fixture.contact().id().toString())).isEqualTo(12);
	}

	@Test
	void readingOneConversationDoesNotTouchOthers() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID first = this.data.start(this.conversations, fixture);
		seedAgentMessages(fixture, first, 5);
		var secondContact = this.directory.ensureContact(fixture.tenantId(), "second-" + UUID.randomUUID(), "Second");
		Caller secondCaller = new Caller(fixture.tenantId(), PrincipalType.CONTACT, secondContact.id().toString(),
				fixture.inbox().id());
		UUID second = this.conversations.startOrResume(secondCaller).conversation().id();

		this.readCursors.advance(fixture.contactCaller(), first, 5);

		assertThat(this.conversations.get(secondCaller, second).unreadCount()).isZero();
		assertThat(this.conversations.get(fixture.contactCaller(), first).unreadCount()).isZero();
	}

	@Test
	void ownMessagesAreNotUnread() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		for (int index = 1; index <= 3; index++) {
			this.messages.send(fixture.agentCaller(), conversation, new SendMessageRequest("agent-" + index, "reply"));
		}

		assertThat(this.conversations.get(fixture.agentCaller(), conversation).unreadCount()).isZero();
		assertThat(this.conversations.get(fixture.contactCaller(), conversation).unreadCount()).isEqualTo(3);
	}

	@Test
	void firstReadRacingFirstSendDoesNotDeadlock() throws Exception {
		for (int iteration = 0; iteration < 50; iteration++) {
			int attempt = iteration;
			ConversationTestData.Fixture fixture = this.data.seed();
			UUID conversation = this.data.start(this.conversations, fixture);
			CyclicBarrier barrier = new CyclicBarrier(2);
			try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				Future<?> read = executor.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					this.readCursors.advance(fixture.agentCaller(), conversation, 0);
					return null;
				});
				Future<?> send = executor.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					this.messages.send(fixture.agentCaller(), conversation,
							new SendMessageRequest("race-" + attempt, "hello"));
					return null;
				});
				read.get(20, TimeUnit.SECONDS);
				send.get(20, TimeUnit.SECONDS);
			}
			assertThat(this.data.cursor(conversation, "AGENT", fixture.agent().id())).isEqualTo(1);
			assertThat(this.data.messageCount(conversation)).isEqualTo(1);
		}
	}

	private void seedAgentMessages(ConversationTestData.Fixture fixture, UUID conversation, int count) {
		for (int index = 1; index <= count; index++) {
			this.messages.send(fixture.agentCaller(), conversation, new SendMessageRequest("seed-" + index, "reply " + index));
		}
	}

}
