package com.byteflock.chatplatform.conversation;

import java.time.Duration;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realtime token endpoints and channel authorization (D14, tenant-security.md §5, R-P18, R-P22): a token is issued only
 * for a channel the caller may read, bound to that exact channel, and the inbox channel's tenant segment is enforced
 * before any lookup.
 */
class RealtimeTokenIT extends PlatformIntegrationTest {

	@Autowired
	ConversationService conversations;

	@Autowired
	TenantDirectory directory;

	@Autowired
	SessionTokens sessionTokens;

	@Autowired
	MeterRegistry meters;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void contactGetsConnectionTokenWithScopedSubject() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();

		MvcTestResult result = this.mvc.get()
			.uri("/api/v1/widget/realtime/connection-token")
			.header("Authorization", "Bearer " + contactToken(fixture))
			.exchange();

		assertThat(result).hasStatusOk();
		String subject = SignedJWT.parse(body(result).get("token").asString()).getJWTClaimsSet().getSubject();
		assertThat(subject).isEqualTo(fixture.tenantId() + ":contact:" + fixture.contact().id());
	}

	@Test
	void contactOwnConversationChannelOk() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		String channel = Channels.conversation(conversation);

		MvcTestResult result = subscription("/api/v1/widget", contactToken(fixture), channel);

		assertThat(result).hasStatusOk();
		var claims = SignedJWT.parse(body(result).get("token").asString()).getJWTClaimsSet();
		assertThat(claims.getStringClaim("channel")).isEqualTo(channel);
		Duration lifetime = Duration.between(claims.getIssueTime().toInstant(),
				claims.getExpirationTime().toInstant());
		assertThat(lifetime).isLessThanOrEqualTo(Duration.ofSeconds(300));
	}

	@Test
	void contactOtherConversationIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ConversationTestData.Fixture otherTenant = this.data.seed();
		var otherContact = this.directory.ensureContact(fixture.tenantId(), "other-" + UUID.randomUUID(), "Other");
		Caller otherCaller = new Caller(fixture.tenantId(), PrincipalType.CONTACT, otherContact.id().toString(),
				fixture.inbox().id());
		UUID otherConversation = this.conversations.startOrResume(otherCaller).conversation().id();
		UUID foreignConversation = this.data.start(this.conversations, otherTenant);

		assertThat(subscription("/api/v1/widget", contactToken(fixture), Channels.conversation(otherConversation)))
			.hasStatus(403);
		assertThat(subscription("/api/v1/widget", contactToken(fixture), Channels.conversation(foreignConversation)))
			.hasStatus(403);
		assertThat(this.meters.counter("realtime.tokens", "type", "subscription", "outcome", "denied").count())
			.isGreaterThanOrEqualTo(2);
	}

	@Test
	void agentConversationChannelOfOwnTenantOk() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		String channel = Channels.conversation(conversation);

		MvcTestResult result = subscription("/api/v1/agent", agentToken(fixture.tenantId()), channel);

		assertThat(result).hasStatusOk();
		assertThat(SignedJWT.parse(body(result).get("token").asString()).getJWTClaimsSet().getStringClaim("channel"))
			.isEqualTo(channel);
	}

	@Test
	void contactInboxChannelIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		String ownInbox = Channels.inbox(fixture.tenantId(), fixture.inbox().id());

		assertThat(subscription("/api/v1/widget", contactToken(fixture), ownInbox)).hasStatus(403);
	}

	@Test
	void agentOwnInboxChannelOk() throws Exception {
		ConversationTestData.Fixture fixture = this.data.seed();
		String channel = Channels.inbox(fixture.tenantId(), fixture.inbox().id());

		MvcTestResult result = subscription("/api/v1/agent", agentToken(fixture.tenantId()), channel);

		assertThat(result).hasStatusOk();
		assertThat(SignedJWT.parse(body(result).get("token").asString()).getJWTClaimsSet().getStringClaim("channel"))
			.isEqualTo(channel);
	}

	@Test
	void agentOtherTenantInboxIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ConversationTestData.Fixture otherTenant = this.data.seed();
		String foreignInbox = Channels.inbox(otherTenant.tenantId(), otherTenant.inbox().id());

		assertThat(subscription("/api/v1/agent", agentToken(fixture.tenantId()), foreignInbox)).hasStatus(403);
	}

	@Test
	void inboxChannelTenantMustMatchInboxOwner() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ConversationTestData.Fixture otherTenant = this.data.seed();
		// Same tenant segment as the caller, but the inbox belongs to another tenant: same answer as an unknown id.
		String mismatched = Channels.inbox(fixture.tenantId(), otherTenant.inbox().id());

		MvcTestResult result = subscription("/api/v1/agent", agentToken(fixture.tenantId()), mismatched);

		assertThat(result).hasStatus(404);
		assertThat(body(result).get("code").asString()).isEqualTo("NOT_FOUND");
	}

	@Test
	void agentConversationOfOtherTenantIs403() {
		ConversationTestData.Fixture fixture = this.data.seed();
		ConversationTestData.Fixture otherTenant = this.data.seed();
		UUID foreignConversation = this.data.start(this.conversations, otherTenant);

		assertThat(subscription("/api/v1/agent", agentToken(fixture.tenantId()),
				Channels.conversation(foreignConversation))).hasStatus(403);
	}

	@Test
	void unknownConversationChannelIs404() {
		ConversationTestData.Fixture fixture = this.data.seed();
		String channel = Channels.conversation(UUID.randomUUID());

		MvcTestResult asAgent = subscription("/api/v1/agent", agentToken(fixture.tenantId()), channel);
		MvcTestResult asContact = subscription("/api/v1/widget", contactToken(fixture), channel);

		assertThat(asAgent).hasStatus(404);
		assertThat(body(asAgent).get("code").asString()).isEqualTo("NOT_FOUND");
		assertThat(asContact).hasStatus(404);
		assertThat(this.meters.counter("realtime.tokens", "type", "subscription", "outcome", "denied").count())
			.isGreaterThanOrEqualTo(2);
	}

	@Test
	void inboxChannelOfOwnTenantUnknownIs404() {
		ConversationTestData.Fixture fixture = this.data.seed();
		String channel = Channels.inbox(fixture.tenantId(), UUID.randomUUID());

		MvcTestResult asAgent = subscription("/api/v1/agent", agentToken(fixture.tenantId()), channel);
		MvcTestResult asContact = subscription("/api/v1/widget", contactToken(fixture), channel);

		assertThat(asAgent).hasStatus(404);
		assertThat(asContact).hasStatus(403);
	}

	@Test
	void malformedChannelIs400() {
		ConversationTestData.Fixture fixture = this.data.seed();

		MvcTestResult result = subscription("/api/v1/agent", agentToken(fixture.tenantId()), "not-a-channel");

		assertThat(result).hasStatus(400);
		assertThat(body(result).get("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void noBearerIs401() {
		ConversationTestData.Fixture fixture = this.data.seed();

		MvcTestResult result = this.mvc.get()
			.uri("/api/v1/widget/realtime/connection-token")
			.exchange();

		assertThat(result).hasStatus(401);
		assertThat(body(result).get("code").asString()).isEqualTo("UNAUTHENTICATED");
	}

	private MvcTestResult subscription(String base, String token, String channel) {
		return this.mvc.get()
			.uri(base + "/realtime/subscription-token?channel=" + channel)
			.header("Authorization", "Bearer " + token)
			.exchange();
	}

	private String contactToken(ConversationTestData.Fixture fixture) {
		return this.sessionTokens
			.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();
	}

	private String agentToken(String tenantId) {
		return this.sessionTokens.issueAgent(tenantId, "agentbob").token();
	}


}
