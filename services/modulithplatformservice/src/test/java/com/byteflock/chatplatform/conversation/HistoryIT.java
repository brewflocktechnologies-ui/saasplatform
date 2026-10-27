package com.byteflock.chatplatform.conversation;

import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.HistoryService;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Paginated history (D13, R-P11): ascending pages, bounded limits, tenant scoping. */
class HistoryIT extends PlatformIntegrationTest {

	@Autowired
	ConversationService conversations;

	@Autowired
	MessageService messages;

	@Autowired
	HistoryService history;

	@Autowired
	TenantDirectory directory;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void pageBeforeReturnsOlderAscendingWithHasMore() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = seedMessages(fixture, 120);

		MessagePage newest = this.history.page(fixture.contactCaller(), conversation, null, null, 50);
		assertThat(newest.items()).extracting(MessageView::sequence).containsExactlyElementsOf(sequences(71, 120));
		assertThat(newest.hasMore()).isTrue();

		MessagePage older = this.history.page(fixture.contactCaller(), conversation, 71L, null, 50);
		assertThat(older.items()).extracting(MessageView::sequence).containsExactlyElementsOf(sequences(21, 70));
		assertThat(older.hasMore()).isTrue();

		MessagePage oldest = this.history.page(fixture.contactCaller(), conversation, 21L, null, 50);
		assertThat(oldest.items()).extracting(MessageView::sequence).containsExactlyElementsOf(sequences(1, 20));
		assertThat(oldest.hasMore()).isFalse();
	}

	@Test
	void pageAfterReturnsCatchUp() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = seedMessages(fixture, 120);

		MessagePage catchUp = this.history.page(fixture.contactCaller(), conversation, null, 100L, 50);

		assertThat(catchUp.items()).extracting(MessageView::sequence).containsExactlyElementsOf(sequences(101, 120));
		assertThat(catchUp.hasMore()).isFalse();
	}

	@Test
	void pagingBeyondRangeAndOversizedLimitAreBounded() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = seedMessages(fixture, 250);

		MessagePage beyond = this.history.page(fixture.contactCaller(), conversation, 1L, null, 50);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.hasMore()).isFalse();

		MessagePage oversized = this.history.page(fixture.contactCaller(), conversation, null, null, 10_000);
		assertThat(oversized.items()).hasSize(200);
		assertThat(oversized.hasMore()).isTrue();

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> this.history.page(fixture.contactCaller(), conversation, null, null, 0));
	}

	@Test
	void beforeAndAfterTogetherIs400() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = seedMessages(fixture, 3);

		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> this.history.page(fixture.contactCaller(), conversation, 2L, 1L, 50));
	}

	@Test
	void historyIsTenantScoped() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = seedMessages(fixture, 3);
		ConversationTestData.Fixture otherTenant = this.data.seed();
		var otherContact = this.directory.ensureContact(otherTenant.tenantId(), "other-" + UUID.randomUUID(), "Other");
		Caller foreignCaller = new Caller(otherTenant.tenantId(), PrincipalType.CONTACT,
				otherContact.id().toString(), otherTenant.inbox().id());

		assertThatExceptionOfType(ConversationAccessDeniedException.class)
			.isThrownBy(() -> this.history.page(otherTenant.agentCaller(), conversation, null, null, 50));
		assertThatExceptionOfType(ConversationAccessDeniedException.class)
			.isThrownBy(() -> this.history.page(foreignCaller, conversation, null, null, 50));
	}

	private UUID seedMessages(ConversationTestData.Fixture fixture, int count) {
		UUID conversation = this.data.start(this.conversations, fixture);
		for (int index = 1; index <= count; index++) {
			this.messages.send(fixture.contactCaller(), conversation, new SendMessageRequest("seed-" + index, "body " + index));
		}
		assertThat(this.data.lastSequence(conversation)).isEqualTo(count);
		return conversation;
	}

	private static List<Long> sequences(long from, long to) {
		return java.util.stream.LongStream.rangeClosed(from, to).boxed().toList();
	}

}
