package com.byteflock.chatplatform.tenancy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.tenancy.internal.LocalSeeder;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenancy over real PostgreSQL: widget sessions (create, resume, refresh window, rate limit), agent local login, caller
 * resolution on protected routes and the idempotent local seed.
 */
@Import({ WidgetProbeController.class, AgentProbeController.class, AdminProbeController.class })
class TenancyIT extends PlatformIntegrationTest {

	@Autowired
	TenantDirectory directory;

	@Autowired
	SessionTokens sessionTokens;

	@Autowired
	RateLimiter rateLimiter;

	@Test
	void widgetSessionUnknownKeyIs404() {
		MvcTestResult result = this.mvc.post()
			.uri("/api/v1/widget/sessions")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("widgetKey", "wk-does-not-exist")))
			.exchange();

		assertThat(result).hasStatus(404);
		assertThat(body(result).get("code").asString()).isEqualTo("NOT_FOUND");
	}

	@Test
	void widgetSessionCreatesAnonymousContact() {
		Inbox inbox = seedInbox();

		MvcTestResult result = postSession(Map.of("widgetKey", inbox.widgetKey()));

		assertThat(result).hasStatus(201);
		JsonNode response = body(result);
		assertThat(response.get("tenantId").asString()).isEqualTo(inbox.tenantId());
		assertThat(response.at("/inbox/id").asString()).isEqualTo(inbox.id().toString());
		Caller caller = this.sessionTokens.verify(response.get("sessionToken").asString());
		assertThat(caller.type()).isEqualTo(PrincipalType.CONTACT);
		assertThat(caller.tenantId()).isEqualTo(inbox.tenantId());
		assertThat(caller.contactId().toString()).isEqualTo(response.at("/contact/id").asString());
		assertThat(this.jdbc.sql("SELECT external_id FROM contacts WHERE id = :id")
			.param("id", caller.contactId())
			.query(String.class)
			.optional()).isEmpty();
	}

	@Test
	void widgetSessionWithExternalIdReusesContact() {
		Inbox inbox = seedInbox();
		String externalId = "visitor-" + suffix();

		MvcTestResult first = postSession(Map.of("widgetKey", inbox.widgetKey(), "externalId", externalId,
				"displayName", "Ada Visitor"));
		MvcTestResult second = postSession(Map.of("widgetKey", inbox.widgetKey(), "externalId", externalId));

		assertThat(first).hasStatus(201);
		assertThat(second).hasStatus(201);
		assertThat(body(second).at("/contact/id").asString()).isEqualTo(body(first).at("/contact/id").asString());
		assertThat(this.jdbc.sql("SELECT count(*) FROM contacts WHERE tenant_id = :tenant AND external_id = :externalId")
			.param("tenant", inbox.tenantId())
			.param("externalId", externalId)
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void widgetSessionRefreshKeepsContact() throws Exception {
		Inbox inbox = seedInbox();
		String externalId = "visitor-" + suffix();
		MvcTestResult first = postSession(Map.of("widgetKey", inbox.widgetKey(), "externalId", externalId));
		String originalToken = body(first).get("sessionToken").asString();

		MvcTestResult refreshed = postSession(Map.of("widgetKey", inbox.widgetKey(), "sessionToken", originalToken));

		assertThat(refreshed).hasStatus(200);
		assertThat(body(refreshed).at("/contact/id").asString()).isEqualTo(body(first).at("/contact/id").asString());
		assertThat(Instant.parse(body(refreshed).get("expiresAt").asString()))
			.isAfterOrEqualTo(Instant.parse(body(first).get("expiresAt").asString()));
		Object originalAuthTime = SignedJWT.parse(originalToken).getJWTClaimsSet().getClaim("auth_time");
		Object refreshedAuthTime = SignedJWT.parse(body(refreshed).get("sessionToken").asString()).getJWTClaimsSet()
			.getClaim("auth_time");
		assertThat(refreshedAuthTime).isEqualTo(originalAuthTime);
		assertThat(this.sessionTokens.verify(body(refreshed).get("sessionToken").asString()).contactId().toString())
			.isEqualTo(body(first).at("/contact/id").asString());
	}

	@Test
	void refreshBeyondMaxAgeIsRejected() {
		Inbox inbox = seedInbox();
		UUID contact = this.directory.ensureContact(inbox.tenantId(), "visitor-" + suffix(), "Ada").id();

		String tooOld = tokenIssuedAt(inbox, contact, Instant.now().minus(Duration.ofDays(30).plusMinutes(1)));
		MvcTestResult rejected = postSession(Map.of("widgetKey", inbox.widgetKey(), "sessionToken", tooOld));
		assertThat(rejected).hasStatus(401);
		assertThat(body(rejected).get("code").asString()).isEqualTo("UNAUTHENTICATED");

		String stillFresh = tokenIssuedAt(inbox, contact,
				Instant.now().minus(Duration.ofDays(30)).plus(Duration.ofMinutes(2)));
		MvcTestResult accepted = postSession(Map.of("widgetKey", inbox.widgetKey(), "sessionToken", stillFresh));
		assertThat(accepted).hasStatus(200);
		assertThat(body(accepted).at("/contact/id").asString()).isEqualTo(contact.toString());
	}

	@Test
	void widgetSessionRateLimited() {
		this.rateLimiter.reset();
		Inbox inbox = seedInbox();

		for (int attempt = 1; attempt <= 10; attempt++) {
			assertThat(postSession(Map.of("widgetKey", inbox.widgetKey())))
				.as("session request %s within the limit", attempt)
				.hasStatus(201);
		}

		MvcTestResult limited = postSession(Map.of("widgetKey", inbox.widgetKey()));

		assertThat(limited).hasStatus(429);
		assertThat(body(limited).get("code").asString()).isEqualTo("RATE_LIMITED");
	}

	@Test
	void agentLocalLoginIssuesAgentSession() {
		Inbox inbox = seedInbox();
		this.directory.ensureAgent(inbox.tenantId(), "agentbob", "Agent Bob");

		MvcTestResult result = this.mvc.post()
			.uri("/api/v1/agent/local-login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("agentId", "agentbob", "tenantId", inbox.tenantId())))
			.exchange();

		assertThat(result).hasStatus(200);
		assertThat(body(result).get("displayName").asString()).isEqualTo("Agent Bob");
		Caller caller = this.sessionTokens.verify(body(result).get("sessionToken").asString());
		assertThat(caller).isEqualTo(new Caller(inbox.tenantId(), PrincipalType.AGENT, "agentbob", null));

		MvcTestResult unknown = this.mvc.post()
			.uri("/api/v1/agent/local-login")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("agentId", "nobody", "tenantId", inbox.tenantId())))
			.exchange();
		assertThat(unknown).hasStatus(404);
	}

	@Test
	void widgetAndAgentRoutesRejectTheOtherPrincipal() {
		Inbox inbox = seedInbox();
		String agentToken = this.sessionTokens.issueAgent(inbox.tenantId(), "agentbob").token();
		UUID contact = this.directory.ensureContact(inbox.tenantId(), "visitor-" + suffix(), "Ada").id();
		String contactToken = this.sessionTokens.issueContact(inbox.tenantId(), contact, inbox.id()).token();

		MvcTestResult wrongType = this.mvc.get().uri("/api/v1/widget/probe").header("Authorization", "Bearer " + agentToken)
			.exchange();
		assertThat(wrongType).hasStatus(403);
		assertThat(body(wrongType).get("code").asString()).isEqualTo("FORBIDDEN");

		assertThat(this.mvc.get().uri("/api/v1/widget/probe").header("Authorization", "Bearer " + contactToken))
			.hasStatusOk();
		assertThat(this.mvc.get().uri("/api/v1/agent/probe").header("Authorization", "Bearer " + contactToken))
			.hasStatus(403);
	}

	@Test
	void missingBearerIs401() {
		MvcTestResult result = this.mvc.get().uri("/api/v1/agent/probe").exchange();

		assertThat(result).hasStatus(401);
		assertThat(body(result).get("code").asString()).isEqualTo("UNAUTHENTICATED");
	}

	@Test
	void adminRouteRequiresAdminToken() {
		MvcTestResult wrong = this.mvc.get().uri("/api/v1/admin/ping").header("X-Admin-Token", "wrong").exchange();
		assertThat(wrong).hasStatus(401);

		MvcTestResult missing = this.mvc.get().uri("/api/v1/admin/ping").exchange();
		assertThat(missing).hasStatus(401);

		MvcTestResult ok = this.mvc.get().uri("/api/v1/admin/ping").header("X-Admin-Token", ADMIN_TOKEN).exchange();
		assertThat(ok).hasStatusOk();
		assertThat(body(ok).get("type").asString()).isEqualTo("ADMIN");
	}

	@Test
	void seedIsIdempotent() {
		LocalSeeder seeder = new LocalSeeder(this.directory, this.jdbc, "tenant-local", "wk_local_demo", "support");

		seeder.run(null);
		seeder.run(null);

		assertThat(count("tenants", "id = 'tenant-local'")).isEqualTo(1);
		assertThat(count("inboxes", "widget_key = 'wk_local_demo'")).isEqualTo(1);
		assertThat(count("contacts", "tenant_id = 'tenant-local' AND external_id = 'userone'")).isEqualTo(1);
		assertThat(count("agents", "tenant_id = 'tenant-local' AND id = 'agentbob'")).isEqualTo(1);
		assertThat(count("retention_policies", "tenant_id = 'tenant-local' AND resolved_retention_days = 30"))
			.isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT allocation_queue_id FROM inboxes WHERE widget_key = 'wk_local_demo'")
			.query(String.class)
			.single()).isEqualTo("support");
	}

	private int count(String table, String predicate) {
		return this.jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + predicate).query(Integer.class).single();
	}

	private Inbox seedInbox() {
		String suffix = suffix();
		String tenantId = "tenant-" + suffix;
		this.directory.ensureTenant(tenantId, "Tenant " + suffix);
		return this.directory.ensureInbox(tenantId, "Website", "wk-" + suffix, "support");
	}

	private MvcTestResult postSession(Map<String, String> body) {
		// The map may contain nulls when a field is intentionally omitted; keep them out of the JSON body.
		Map<String, String> payload = new HashMap<>(body);
		payload.values().removeIf((value) -> value == null);
		return this.mvc.post()
			.uri("/api/v1/widget/sessions")
			.contentType(MediaType.APPLICATION_JSON)
			.content(json(payload))
			.exchange();
	}

	private String tokenIssuedAt(Inbox inbox, UUID contact, Instant issuedAt) {
		SessionTokens past = new SessionTokens(PlatformIntegrationTest.SESSION_SECRET, Duration.ofHours(24),
				Duration.ofDays(30), Duration.ofHours(12), Clock.fixed(issuedAt, ZoneOffset.UTC));
		return past.issueContact(inbox.tenantId(), contact, inbox.id()).token();
	}

	private String json(Map<String, String> payload) {
		return this.json.writeValueAsString(payload);
	}

	private static String suffix() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
