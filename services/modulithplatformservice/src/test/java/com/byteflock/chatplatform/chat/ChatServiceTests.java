package com.byteflock.chatplatform.chat;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.byteflock.chatplatform.chat.ChatDtos.Conversation;
import com.byteflock.chatplatform.chat.ChatDtos.Message;
import com.byteflock.chatplatform.chat.ChatDtos.MessageCreatedEvent;
import com.byteflock.chatplatform.realtime.RealtimePublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatServiceTests {

	final UUID conversationId = UUID.randomUUID();

	final ChatRepository repository = mock(ChatRepository.class);

	final RealtimePublisher publisher = mock(RealtimePublisher.class);

	final TransactionTemplate tx = mock(TransactionTemplate.class);

	final ChatService service = new ChatService(this.repository, this.publisher, this.tx, new SimpleMeterRegistry());

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		given(this.tx.execute(any())).willAnswer((inv) -> inv.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
		given(this.repository.findById(this.conversationId)).willReturn(Optional.of(new Conversation(this.conversationId,
				"userone", "agentbob", "OPEN", OffsetDateTime.now(), OffsetDateTime.now(), null, null)));
	}

	@Test
	void databaseFailureNeverPublishes() {
		given(this.repository.insertMessage(any(), anyString(), anyString(), anyString()))
			.willThrow(new DataAccessResourceFailureException("db down"));

		assertThatExceptionOfType(DataAccessResourceFailureException.class)
			.isThrownBy(() -> this.service.send(this.conversationId, "userone", "hello"));
		verifyNoInteractions(this.publisher);
	}

	@Test
	void publishesMessageCreatedToConversationChannelAfterPersisting() {
		Message saved = new Message(UUID.randomUUID(), this.conversationId, "userone", "VISITOR", "hello",
				OffsetDateTime.now());
		given(this.repository.insertMessage(this.conversationId, "userone", "VISITOR", "hello")).willReturn(saved);
		given(this.publisher.publish(anyString(), any())).willReturn(true);

		var response = this.service.send(this.conversationId, "userone", "  hello  ");

		assertThat(response.realtimeDelivery()).isEqualTo("PUBLISHED");
		verify(this.publisher).publish(eq("chat:conversation:" + this.conversationId),
				eq(new MessageCreatedEvent("message.created", this.conversationId, saved)));
		verify(this.publisher).publish(eq("chat:inbox"), any());
	}

	@Test
	void publishFailureKeepsMessageAndReportsFailedDelivery() {
		Message saved = new Message(UUID.randomUUID(), this.conversationId, "agentbob", "AGENT", "hi",
				OffsetDateTime.now());
		given(this.repository.insertMessage(any(), anyString(), anyString(), anyString())).willReturn(saved);
		given(this.publisher.publish(anyString(), any())).willReturn(false);

		var response = this.service.send(this.conversationId, "agentbob", "hi");

		assertThat(response.message()).isEqualTo(saved);
		assertThat(response.realtimeDelivery()).isEqualTo("FAILED");
	}

	@Test
	void rejectsInvalidInputBeforeTouchingTheDatabase() {
		assertThat(status(() -> this.service.send(this.conversationId, "userone", "   \n\t "))).isEqualTo(400);
		assertThat(status(() -> this.service.send(this.conversationId, "userone", "x".repeat(4001)))).isEqualTo(400);
		assertThat(status(() -> this.service.send(this.conversationId, "mallory", "hi"))).isEqualTo(400);
		assertThat(status(() -> this.service.send(this.conversationId, null, "hi"))).isEqualTo(400);
		verify(this.repository, never()).insertMessage(any(), anyString(), anyString(), anyString());
		verifyNoInteractions(this.publisher);
	}

	@Test
	void acceptsExactlyMaxLengthCountingCodePoints() {
		assertThat(ChatService.normalize("😀".repeat(4000))).hasSize(8000);
		assertThat(status(() -> ChatService.normalize("😀".repeat(4001)))).isEqualTo(400);
	}

	@Test
	void unknownConversationIs404AndNonParticipantIs403() {
		assertThat(status(() -> this.service.send(UUID.randomUUID(), "userone", "hi"))).isEqualTo(404);
		given(this.repository.findById(this.conversationId)).willReturn(Optional.of(new Conversation(
				this.conversationId, "someone-else", "agentbob", "OPEN", OffsetDateTime.now(), OffsetDateTime.now(),
				null, null)));
		assertThat(status(() -> this.service.history(this.conversationId, "userone"))).isEqualTo(403);
	}

	@Test
	void inboxIsAgentOnly() {
		assertThat(status(() -> this.service.inbox("userone"))).isEqualTo(403);
		assertThat(status(() -> this.service.inbox(null))).isEqualTo(400);
	}

	private static int status(Runnable call) {
		try {
			call.run();
		}
		catch (ResponseStatusException ex) {
			return ex.getStatusCode().value();
		}
		return 200;
	}

}
