package com.byteflock.chatplatform.conversation;

import java.util.Map;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.TypingService;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Typing (D12, D17): ephemeral, rate limited per principal and conversation, published directly to the conversation
 * channel, never persisted, and never failing the request because Centrifugo is down.
 */
class TypingIT extends PlatformIntegrationTest {

	@Autowired
	TypingService typingService;

	@Autowired
	ConversationService conversations;

	@Autowired
	TenantDirectory directory;

	@Autowired
	SessionTokens sessionTokens;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void participantTypingIsPublishedToConversationChannelOnly() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		String token = contactToken(fixture);

		MvcTestResult result = typing("/api/v1/widget", token, conversation, true);

		assertThat(result).hasStatus(202);
		assertThat(CENTRIFUGO.requestsFor("/api/publish")).hasSize(1);
		JsonNode body = this.json.readTree(CENTRIFUGO.requestsFor("/api/publish").getFirst().body());
		assertThat(body.get("channel").asString()).isEqualTo(Channels.conversation(conversation));
		assertThat(body.at("/data/eventType").asString()).isEqualTo("typing");
		assertThat(body.at("/data/data/typing").asBoolean()).isTrue();
		assertThat(body.at("/data/data/participantType").asString()).isEqualTo("CONTACT");
	}

	@Test
	void typingIsRateLimitedAndNeverPersisted() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		int accepted = 0;
		int limited = 0;
		for (int attempt = 0; attempt < 50; attempt++) {
			MvcTestResult result = typing("/api/v1/widget", contactToken(fixture), conversation, true);
			if (result.getResponse().getStatus() == 202) {
				accepted++;
			}
			else if (result.getResponse().getStatus() == 429) {
				limited++;
			}
		}

		assertThat(accepted).isEqualTo(1);
		assertThat(limited).isEqualTo(49);
		assertThat(CENTRIFUGO.requestsFor("/api/publish")).hasSize(1);
		assertThat(this.jdbc.sql("SELECT count(*) FROM messages WHERE conversation_id = :id")
			.param("id", conversation)
			.query(Integer.class)
			.single()).isZero();
		assertThat(this.jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id AND event_type = 'message.created'")
			.param("id", conversation.toString())
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void stopTypingAfterIntervalIsAccepted() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		assertThat(typing("/api/v1/widget", contactToken(fixture), conversation, true)).hasStatus(202);
		this.testClock.advance(java.time.Duration.ofSeconds(2));

		MvcTestResult stop = typing("/api/v1/widget", contactToken(fixture), conversation, false);

		assertThat(stop).hasStatus(202);
		assertThat(CENTRIFUGO.requestsFor("/api/publish")).hasSize(2);
		JsonNode body = this.json.readTree(CENTRIFUGO.requestsFor("/api/publish").get(1).body());
		assertThat(body.at("/data/data/typing").asBoolean()).isFalse();
	}

	@Test
	void nonParticipantIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		var otherContact = this.directory.ensureContact(fixture.tenantId(), "other-" + UUID.randomUUID(), "Other");
		Caller other = new Caller(fixture.tenantId(), PrincipalType.CONTACT, otherContact.id().toString(),
				fixture.inbox().id());

		assertThatExceptionOfType(ConversationAccessDeniedException.class)
			.isThrownBy(() -> this.typingService.typing(other, conversation, true));
	}

	@Test
	void crossTenantIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ConversationTestData.Fixture otherTenant = this.data.seed();

		assertThatExceptionOfType(ConversationAccessDeniedException.class)
			.isThrownBy(() -> this.typingService.typing(otherTenant.agentCaller(), conversation, true));
	}

	@Test
	void centrifugoDownStillReturns202() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		CENTRIFUGO.setDown(true);

		assertThat(typing("/api/v1/widget", contactToken(fixture), conversation, true)).hasStatus(202);
	}

	private MvcTestResult typing(String base, String token, UUID conversation, boolean typing) {
		return this.mvc.post()
			.uri(base + "/conversations/" + conversation + "/typing")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + token)
			.content(this.json.writeValueAsString(Map.of("typing", typing)))
			.exchange();
	}

	private String contactToken(ConversationTestData.Fixture fixture) {
		return this.sessionTokens
			.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();
	}

}
