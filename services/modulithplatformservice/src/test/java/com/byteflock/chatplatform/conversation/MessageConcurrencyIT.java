package com.byteflock.chatplatform.conversation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency of the send path: identical concurrent sends produce one row, distinct sends get a contiguous, gap-free
 * sequence, and sends to different conversations do not serialize or deadlock (D4, D5).
 */
class MessageConcurrencyIT extends PlatformIntegrationTest {

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
	void identicalConcurrentSendsProduceOneRow() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		List<MessageService.SendResult> results = concurrently(20,
				(index) -> this.messages.send(fixture.contactCaller(), conversation,
						new SendMessageRequest("same-key", "same body")));

		assertThat(results).hasSize(20);
		assertThat(results).allSatisfy((result) -> assertThat(result.message().id()).isEqualTo(results.get(0).message().id()));
		assertThat(results.stream().filter(MessageService.SendResult::created).count()).isEqualTo(1);
		assertThat(this.data.messageCount(conversation)).isEqualTo(1);
		assertThat(this.data.lastSequence(conversation)).isEqualTo(1);
	}

	@Test
	void distinctConcurrentSendsHaveContiguousSequences() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		List<MessageService.SendResult> results = concurrently(20, (index) -> this.messages.send(
				fixture.contactCaller(), conversation, new SendMessageRequest("key-" + index, "message " + index)));

		List<Long> sequences = results.stream().map((result) -> result.message().sequence()).sorted().toList();
		assertThat(sequences).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 20).boxed().toList());
		assertThat(this.data.lastSequence(conversation)).isEqualTo(20);
		assertThat(this.data.outboxCount(conversation, "message.created")).isEqualTo(20);
	}

	@Test
	void concurrentSendsAcrossConversationsDoNotSerialize() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID first = this.data.start(this.conversations, fixture);
		var otherInboxContact = this.directory.ensureContact(fixture.tenantId(), "second-" + UUID.randomUUID(), "Second");
		Caller secondCaller = new Caller(fixture.tenantId(), com.byteflock.chatplatform.tenancy.PrincipalType.CONTACT,
				otherInboxContact.id().toString(), fixture.inbox().id());
		UUID second = this.conversations.startOrResume(secondCaller).conversation().id();
		assertThat(second).isNotEqualTo(first);

		Instant start = Instant.now();
		List<MessageService.SendResult> results = concurrently(2, (index) -> this.messages.send(
				(index == 0) ? fixture.contactCaller() : secondCaller, (index == 0) ? first : second,
				new SendMessageRequest("cross-" + index, "parallel")));
		Duration elapsed = Duration.between(start, Instant.now());

		assertThat(results).hasSize(2);
		assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
	}

	private List<MessageService.SendResult> concurrently(int threads, IndexedTask task) throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(threads);
		List<MessageService.SendResult> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<MessageService.SendResult>> futures = new ArrayList<>();
			for (int index = 0; index < threads; index++) {
				int slot = index;
				futures.add(executor.submit((Callable<MessageService.SendResult>) () -> {
					barrier.await(10, TimeUnit.SECONDS);
					return task.run(slot);
				}));
			}
			for (Future<MessageService.SendResult> future : futures) {
				results.add(future.get(20, TimeUnit.SECONDS));
			}
		}
		return results;
	}

	@FunctionalInterface
	interface IndexedTask {

		MessageService.SendResult run(int index);

	}

}
