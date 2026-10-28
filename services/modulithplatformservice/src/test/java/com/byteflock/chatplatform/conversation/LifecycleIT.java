package com.byteflock.chatplatform.conversation;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Lifecycle over real PostgreSQL: start/resume, the effects of every transition on status, archive marker, assignment
 * projection, state events, outbox rows and {@code version} (docs/architecture/domain-model.md sections 5 and 6).
 */
class LifecycleIT extends PlatformIntegrationTest {

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
	void startOrResumeCreatesThenResumes() {
		ConversationTestData.Fixture fixture = this.data.seed();

		ConversationService.StartResult created = this.conversations.startOrResume(fixture.contactCaller());
		ConversationService.StartResult resumed = this.conversations.startOrResume(fixture.contactCaller());

		UUID conversation = created.conversation().id();
		assertThat(created.created()).isTrue();
		assertThat(resumed.created()).isFalse();
		assertThat(resumed.conversation().id()).isEqualTo(conversation);
		assertThat(this.data.assignmentState(conversation)).isEqualTo("REQUESTED");
		assertThat(this.data.assignmentNextAttemptAt(conversation)).isNotNull();
		assertThat(this.data.participantCount(conversation, "CONTACT", fixture.contact().id().toString())).isEqualTo(1);
		assertThat(this.data.outboxCount(conversation, "conversation.created")).isEqualTo(1);
		assertThat(this.data.outboxChannels(conversation, "conversation.created"))
			.isEqualTo("chat:inbox:" + fixture.tenantId() + ":" + fixture.inbox().id());
	}

	@Test
	void resolveSetsResolvedAtAndStateEventAndRelease() {
		for (String held : List.of("REQUESTED", "QUEUED", "ASSIGNED", "REOPEN_PENDING", "FAILED")) {
			ConversationTestData.Fixture fixture = this.data.seed();
			UUID conversation = this.data.start(this.conversations, fixture);
			this.data.setAssignment(conversation, held, "agentbob", OffsetDateTime.now().plusSeconds(60));
			long versionBefore = this.data.version(conversation);

			ConversationView view = this.conversations.resolve(fixture.agentCaller(), conversation,
					new CommandRequest(null, "solved"));

			assertThat(view.status()).isEqualTo(ConversationStatus.RESOLVED);
			assertThat(this.data.resolvedAt(conversation)).isNotNull();
			assertThat(this.data.assignmentState(conversation)).as("resolve from %s", held)
				.isEqualTo("RELEASE_PENDING");
			assertThat(this.data.version(conversation)).isEqualTo(versionBefore + 1);
			assertThat(this.data.stateEventCount(conversation, "RESOLVED")).isEqualTo(1);
			assertThat(this.data.outboxCount(conversation, "conversation.updated")).isEqualTo(1);
			String payload = this.data.outboxPayloads(conversation, "conversation.updated").get(0);
			assertThat(this.json.readTree(payload).get("data").get("change").asString()).isEqualTo("RESOLVED");
			assertThat(this.json.readTree(payload).at("/data/conversation/assignment").size()).isEqualTo(2);
		}
		for (String free : List.of("NONE", "RELEASE_PENDING")) {
			ConversationTestData.Fixture fixture = this.data.seed();
			UUID conversation = this.data.start(this.conversations, fixture);
			this.data.setAssignment(conversation, free, null, null);

			this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));

			assertThat(this.data.assignmentState(conversation)).as("resolve from %s", free).isEqualTo(free);
		}
	}

	@Test
	void reopenFromResolvedRequestsReassignment() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		OffsetDateTime resolvedAt = this.data.resolvedAt(conversation);

		ConversationView view = this.conversations.reopen(fixture.agentCaller(), conversation,
				new CommandRequest(null, null));

		assertThat(view.status()).isEqualTo(ConversationStatus.OPEN);
		assertThat(this.data.assignmentState(conversation)).isEqualTo("REOPEN_PENDING");
		assertThat(this.data.stateEventCount(conversation, "REOPENED")).isEqualTo(1);
		assertThat(this.data.resolvedAt(conversation)).isEqualTo(resolvedAt);
	}

	@Test
	void contactMessageIntoPendingReopens() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.markPending(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		long versionBefore = this.data.version(conversation);
		this.messages.send(fixture.agentCaller(), conversation, new SendMessageRequest("agent-pending", "waiting"));
		assertThat(this.data.status(conversation)).isEqualTo("PENDING");
		assertThat(this.data.version(conversation)).isEqualTo(versionBefore);

		this.messages.send(fixture.contactCaller(), conversation, new SendMessageRequest("contact-back", "hello"));

		assertThat(this.data.status(conversation)).isEqualTo("OPEN");
		assertThat(this.data.version(conversation)).isEqualTo(versionBefore + 1);
		assertThat(this.data.stateEventCount(conversation, "REOPENED_BY_CONTACT")).isEqualTo(1);
	}

	@Test
	void archiveAndUnarchiveAreIndependentOfStatus() {
		for (ConversationStatus status : List.of(ConversationStatus.OPEN, ConversationStatus.PENDING,
				ConversationStatus.RESOLVED)) {
			ConversationTestData.Fixture fixture = this.data.seed();
			UUID conversation = this.data.start(this.conversations, fixture);
			if (status == ConversationStatus.PENDING) {
				this.conversations.markPending(fixture.agentCaller(), conversation, new CommandRequest(null, null));
			}
			if (status == ConversationStatus.RESOLVED) {
				this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));
			}

			this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, "archive"));
			assertThat(this.data.status(conversation)).isEqualTo(status.name());
			assertThat(this.data.stateEventCount(conversation, "ARCHIVED")).isEqualTo(1);

			this.conversations.unarchive(fixture.agentCaller(), conversation, new CommandRequest(null, null));
			assertThat(this.data.status(conversation)).isEqualTo(status.name());
			assertThat(this.data.stateEventCount(conversation, "UNARCHIVED")).isEqualTo(1);
		}
	}

	@Test
	void repeatedCommandIsNoOp200() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ConversationView first = this.conversations.resolve(fixture.agentCaller(), conversation,
				new CommandRequest(null, null));
		long version = first.version();
		int events = this.data.stateEventCount(conversation, "RESOLVED");
		int outbox = this.data.outboxCount(conversation, "conversation.updated");

		ConversationView second = this.conversations.resolve(fixture.agentCaller(), conversation,
				new CommandRequest(version - 1, "stale retry"));

		assertThat(second.version()).isEqualTo(version);
		assertThat(second.status()).isEqualTo(ConversationStatus.RESOLVED);
		assertThat(this.data.stateEventCount(conversation, "RESOLVED")).isEqualTo(events);
		assertThat(this.data.outboxCount(conversation, "conversation.updated")).isEqualTo(outbox);
	}

	@Test
	void staleVersionIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.conversations.resolve(fixture.agentCaller(), conversation,
					new CommandRequest(999L, null)))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("STALE_VERSION"));
		assertThat(this.data.status(conversation)).isEqualTo("OPEN");
	}

	@Test
	void forbiddenTransitionIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.conversations.markPending(fixture.agentCaller(), conversation,
					new CommandRequest(null, null)))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("INVALID_TRANSITION"));
	}

	@Test
	void archivedConversationRejectsLifecycleCommands() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, null));

		assertThat(this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null))
			.status()).isEqualTo(ConversationStatus.RESOLVED);
		assertThat(this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, null))
			.status()).isEqualTo(ConversationStatus.RESOLVED);
		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.conversations.markPending(fixture.agentCaller(), conversation,
					new CommandRequest(null, null)))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CONVERSATION_ARCHIVED"));

		ConversationTestData.Fixture openFixture = this.data.seed();
		UUID archivedOpen = this.data.start(this.conversations, openFixture);
		this.conversations.archive(openFixture.agentCaller(), archivedOpen, new CommandRequest(null, null));
		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.conversations.reopen(openFixture.agentCaller(), archivedOpen,
					new CommandRequest(null, null)))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CONVERSATION_ARCHIVED"));
	}

	@Test
	void archiveReleasesAssignment() {
		for (String held : List.of("REQUESTED", "QUEUED", "ASSIGNED", "REOPEN_PENDING", "FAILED")) {
			ConversationTestData.Fixture fixture = this.data.seed();
			UUID conversation = this.data.start(this.conversations, fixture);
			OffsetDateTime next = OffsetDateTime.now().plusSeconds(60);
			this.data.setAssignment(conversation, held, "agentbob", next);
			long versionBefore = this.data.version(conversation);

			this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, "done"));

			assertThat(this.data.assignmentState(conversation)).as("archive from %s", held)
				.isEqualTo("RELEASE_PENDING");
			assertThat(this.data.version(conversation)).isEqualTo(versionBefore + 1);
			assertThat(this.data.outboxCount(conversation, "conversation.updated")).isEqualTo(1);
			if ("FAILED".equals(held)) {
				// A FAILED row's time is a poll schedule, not a lease: the release must run now (R-P20).
				assertThat(this.data.assignmentNextAttemptAt(conversation).toInstant())
					.isBefore(next.toInstant());
			}
		}
	}

	@Test
	void unarchiveRequestsReassignment() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID open = this.data.start(this.conversations, fixture);
		this.conversations.archive(fixture.agentCaller(), open, new CommandRequest(null, null));
		this.data.setAssignment(open, "NONE", null, null);

		this.conversations.unarchive(fixture.agentCaller(), open, new CommandRequest(null, null));
		assertThat(this.data.status(open)).isEqualTo("OPEN");
		assertThat(this.data.assignmentState(open)).isEqualTo("REOPEN_PENDING");
		assertThat(this.data.stateEventCount(open, "UNARCHIVED")).isEqualTo(1);

		ConversationTestData.Fixture resolvedFixture = this.data.seed();
		UUID resolved = this.data.start(this.conversations, resolvedFixture);
		this.conversations.resolve(resolvedFixture.agentCaller(), resolved, new CommandRequest(null, null));
		this.data.setAssignment(resolved, "RELEASE_PENDING", null, null);
		this.conversations.archive(resolvedFixture.agentCaller(), resolved, new CommandRequest(null, null));

		this.conversations.unarchive(resolvedFixture.agentCaller(), resolved, new CommandRequest(null, null));
		assertThat(this.data.assignmentState(resolved)).isEqualTo("RELEASE_PENDING");
	}

	@Test
	void unarchivePurgedConversationIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, null));
		this.data.purge(conversation);

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.conversations.unarchive(fixture.agentCaller(), conversation,
					new CommandRequest(null, null)))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("INVALID_TRANSITION"));
	}

	@Test
	void unarchiveOrReopenWhileAnotherActiveExistsIs409() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID archivedConversation = this.data.start(this.conversations, fixture);
		this.conversations.archive(fixture.agentCaller(), archivedConversation, new CommandRequest(null, null));
		UUID active = this.data.start(this.conversations, fixture);
		assertThat(active).isNotEqualTo(archivedConversation);

		assertThatExceptionOfType(ConversationConflictException.class)
			.isThrownBy(() -> this.conversations.unarchive(fixture.agentCaller(), archivedConversation,
					new CommandRequest(null, null)))
			.satisfies((ex) -> assertThat(ex.code()).isEqualTo("CONVERSATION_ACTIVE_EXISTS"));
	}

	@Test
	void listForInboxQueueMineAllAndArchivedFilters() {
		ConversationTestData.Fixture fixture = this.data.seed();
		List<UUID> conversations = new ArrayList<>();
		for (int index = 0; index < 5; index++) {
			var contact = this.directory.ensureContact(fixture.tenantId(), "list-" + index, "Contact " + index);
			Caller caller = new Caller(fixture.tenantId(), PrincipalType.CONTACT, contact.id().toString(),
					fixture.inbox().id());
			conversations.add(this.conversations.startOrResume(caller).conversation().id());
		}
		this.data.setAssignment(conversations.get(0), "ASSIGNED", "agentbob", null);
		this.data.setAssignment(conversations.get(1), "QUEUED", null, null);
		this.data.setAssignment(conversations.get(2), "REQUESTED", null, null);
		this.data.setAssignment(conversations.get(3), "FAILED", null, null);
		this.conversations.archive(fixture.agentCaller(), conversations.get(4), new CommandRequest(null, null));

		InboxPage queue = this.conversations.listForAgent(fixture.agentCaller(), fixture.inbox().id(), "queue", false,
				null, 10);
		assertThat(queue.items()).hasSize(3);
		assertThat(queue.items()).allSatisfy((view) -> assertThat(view.archivedAt()).isNull());

		InboxPage mine = this.conversations.listForAgent(fixture.agentCaller(), fixture.inbox().id(), "mine", false,
				null, 10);
		assertThat(mine.items()).hasSize(1);
		assertThat(mine.items().get(0).id()).isEqualTo(conversations.get(0));

		InboxPage archived = this.conversations.listForAgent(fixture.agentCaller(), fixture.inbox().id(), "all", true,
				null, 10);
		assertThat(archived.items()).hasSize(1);
		assertThat(archived.items().get(0).id()).isEqualTo(conversations.get(4));

		List<UUID> walked = new ArrayList<>();
		String cursor = null;
		do {
			InboxPage page = this.conversations.listForAgent(fixture.agentCaller(), fixture.inbox().id(), "all", false,
					cursor, 2);
			page.items().forEach((view) -> walked.add(view.id()));
			cursor = page.nextCursor();
		}
		while (cursor != null);
		assertThat(walked).hasSize(4).doesNotHaveDuplicates();
	}

	@Test
	void versionIncrementsOnStateWritesOnly() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		long version = this.data.version(conversation);

		version = expectVersion(conversation, version + 1,
				() -> this.conversations.markPending(fixture.agentCaller(), conversation, new CommandRequest(null, null)));
		version = expectVersion(conversation, version + 1,
				() -> this.conversations.reopen(fixture.agentCaller(), conversation, new CommandRequest(null, null)));
		version = expectVersion(conversation, version + 1,
				() -> this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, null)));
		version = expectVersion(conversation, version + 1,
				() -> this.conversations.reopen(fixture.agentCaller(), conversation, new CommandRequest(null, null)));
		version = expectVersion(conversation, version + 1,
				() -> this.conversations.archive(fixture.agentCaller(), conversation, new CommandRequest(null, null)));
		version = expectVersion(conversation, version + 1,
				() -> this.conversations.unarchive(fixture.agentCaller(), conversation, new CommandRequest(null, null)));

		// Message activity, a read cursor write and a repeated no-op command never bump the version.
		this.messages.send(fixture.contactCaller(), conversation, new SendMessageRequest("version-1", "hello"));
		assertThat(this.data.version(conversation)).isEqualTo(version);
		this.readCursors.advance(fixture.contactCaller(), conversation, 1);
		assertThat(this.data.version(conversation)).isEqualTo(version);
		this.conversations.reopen(fixture.agentCaller(), conversation, new CommandRequest(version - 1, null));
		assertThat(this.data.version(conversation)).isEqualTo(version);
	}

	private long expectVersion(UUID conversation, long expected, Runnable action) {
		action.run();
		assertThat(this.data.version(conversation)).isEqualTo(expected);
		return expected;
	}

}
