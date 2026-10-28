package com.byteflock.chatplatform.conversation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.Inbox;
import com.byteflock.chatplatform.tenancy.RateLimiter;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The widget and agent HTTP surface over real PostgreSQL: token flow, start/resume, send/replay, history, read cursors,
 * lifecycle commands, tenant isolation, problem codes and the assignment visibility rules (R-P3, R-P29).
 */
class ConversationApiIT extends PlatformIntegrationTest {

	@Autowired
	ConversationService conversations;

	@Autowired
	TenantDirectory directory;

	@Autowired
	SessionTokens sessionTokens;

	@Autowired
	RateLimiter rateLimiter;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
		// Rate-limit buckets live in memory and are keyed by principal: start every test with empty buckets.
		this.rateLimiter.reset();
	}

	@Test
	void widgetFlow() {
		ConversationTestData.Fixture fixture = this.data.seed();
		MvcTestResult session = post("/api/v1/widget/sessions", null,
				Map.of("widgetKey", fixture.inbox().widgetKey(), "externalId", "visitor-" + UUID.randomUUID()));
		assertThat(session).hasStatus(201);
		String token = body(session).get("sessionToken").asString();

		MvcTestResult start = post("/api/v1/widget/conversations", token, null);
		assertThat(start).hasStatus(201);
		JsonNode started = body(start);
		UUID conversation = UUID.fromString(started.get("id").asString());
		assertThat(started.at("/assignment/state").asString()).isEqualTo("REQUESTED");
		assertThat(started.get("assignment").size()).isEqualTo(2);

		assertThat(get("/api/v1/widget/conversations/" + conversation, token)).hasStatusOk();

		MvcTestResult send = post("/api/v1/widget/conversations/" + conversation + "/messages", token,
				Map.of("clientMessageId", "client-1", "body", "hello"));
		assertThat(send).hasStatus(201);
		String messageId = body(send).get("id").asString();

		MvcTestResult replay = post("/api/v1/widget/conversations/" + conversation + "/messages", token,
				Map.of("clientMessageId", "client-1", "body", "hello"));
		assertThat(replay).hasStatus(200);
		assertThat(body(replay).get("id").asString()).isEqualTo(messageId);

		MvcTestResult history = get("/api/v1/widget/conversations/" + conversation + "/messages", token);
		assertThat(history).hasStatusOk();
		assertThat(body(history).get("items").size()).isEqualTo(1);
		assertThat(body(history).get("hasMore").asBoolean()).isFalse();

		assertThat(put("/api/v1/widget/conversations/" + conversation + "/read", token,
				Map.of("lastReadSequence", 1))).hasStatus(204);
		assertThat(body(get("/api/v1/widget/conversations/" + conversation, token)).get("unreadCount").asLong()).isZero();
	}

	@Test
	void agentFlow() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		String token = agentToken(fixture.tenantId());

		MvcTestResult inboxes = get("/api/v1/agent/inboxes", token);
		assertThat(inboxes).hasStatusOk();
		assertThat(body(inboxes).size()).isEqualTo(1);
		assertThat(body(inboxes).get(0).get("id").asString()).isEqualTo(fixture.inbox().id().toString());

		MvcTestResult queue = get("/api/v1/agent/inboxes/" + fixture.inbox().id()
				+ "/conversations?view=queue&limit=10", token);
		assertThat(queue).hasStatusOk();
		assertThat(body(queue).get("items").size()).isEqualTo(1);

		assertThat(get("/api/v1/agent/conversations/" + conversation, token)).hasStatusOk();

		MvcTestResult send = post("/api/v1/agent/conversations/" + conversation + "/messages", token,
				Map.of("clientMessageId", "agent-1", "body", "on it"));
		assertThat(send).hasStatus(201);

		assertThat(post("/api/v1/agent/conversations/" + conversation + "/resolve", token, Map.of("reason", "solved")))
			.hasStatusOk();
		MvcTestResult reopened = post("/api/v1/agent/conversations/" + conversation + "/reopen", token, null);
		assertThat(body(reopened).get("status").asString()).isEqualTo("OPEN");
		assertThat(post("/api/v1/agent/conversations/" + conversation + "/pending", token, null)).hasStatusOk();
		assertThat(post("/api/v1/agent/conversations/" + conversation + "/archive", token, Map.of("reason", "done")))
			.hasStatusOk();
		MvcTestResult archived = get("/api/v1/agent/inboxes/" + fixture.inbox().id()
				+ "/conversations?archived=true&limit=10", token);
		assertThat(body(archived).get("items").size()).isEqualTo(1);
		MvcTestResult unarchived = post("/api/v1/agent/conversations/" + conversation + "/unarchive", token, null);
		assertThat(body(unarchived).get("archivedAt").isNull()).isTrue();
	}

	@Test
	void contactCannotReadAnotherInboxConversation() {
		ConversationTestData.Fixture fixture = this.data.seed();
		Inbox secondInbox = this.directory.ensureInbox(fixture.tenantId(), "Sales", "wk-sales-" + UUID.randomUUID(),
				"support");
		var otherContact = this.directory.ensureContact(fixture.tenantId(), "other-" + UUID.randomUUID(), "Other");
		Caller otherCaller = new Caller(fixture.tenantId(), com.byteflock.chatplatform.tenancy.PrincipalType.CONTACT,
				otherContact.id().toString(), secondInbox.id());		UUID conversation = this.conversations.startOrResume(otherCaller).conversation().id();
		String token = this.sessionTokens.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();

		MvcTestResult result = get("/api/v1/widget/conversations/" + conversation, token);

		assertThat(result).hasStatus(403);
		assertThat(body(result).get("code").asString()).isEqualTo("FORBIDDEN");
	}

	@Test
	void sameAgentIdInTwoTenantsIsIsolated() {
		ConversationTestData.Fixture first = this.data.seed();
		ConversationTestData.Fixture second = this.data.seed();
		UUID conversation = this.data.start(this.conversations, first);
		String tokenB = agentToken(second.tenantId());

		MvcTestResult list = get("/api/v1/agent/inboxes/" + second.inbox().id() + "/conversations?limit=10", tokenB);
		assertThat(list).hasStatusOk();
		assertThat(body(list).get("items").size()).isZero();
		assertThat(get("/api/v1/agent/conversations/" + conversation, tokenB)).hasStatus(403);
	}

	@Test
	void validationErrorsAreProblemJsonWithCode() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		String token = this.sessionTokens.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();

		MvcTestResult emptyBody = post("/api/v1/widget/conversations/" + conversation + "/messages", token,
				Map.of("body", "no key"));
		assertThat(emptyBody).hasStatus(400)
			.hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(body(emptyBody).get("code").asString()).isEqualTo("VALIDATION_FAILED");

		MvcTestResult badLimit = get("/api/v1/widget/conversations/" + conversation + "/messages?limit=0", token);
		assertThat(badLimit).hasStatus(400);
		assertThat(body(badLimit).get("code").asString()).isEqualTo("VALIDATION_FAILED");

		MvcTestResult malformed = this.mvc.post()
			.uri("/api/v1/widget/conversations/" + conversation + "/messages")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + token)
			.content("{not json")
			.exchange();
		assertThat(malformed).hasStatus(400);
		assertThat(body(malformed).get("code").asString()).isEqualTo("VALIDATION_FAILED");
		String problem = body(malformed).toString();
		assertThat(problem).doesNotContainIgnoringCase("sql").doesNotContainIgnoringCase("exception");
		assertThat(problem).doesNotContain("Boot").doesNotContain("org.springframework");
	}

	@Test
	void messageRateLimit() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		String token = this.sessionTokens.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();

		for (int index = 1; index <= 30; index++) {
			MvcTestResult result = post("/api/v1/widget/conversations/" + conversation + "/messages", token,
					Map.of("clientMessageId", "limit-" + index, "body", "message " + index));
			assertThat(result).as("message %s within the limit", index).hasStatus(201);
		}

		MvcTestResult limited = post("/api/v1/widget/conversations/" + conversation + "/messages", token,
				Map.of("clientMessageId", "limit-31", "body", "one too many"));
		assertThat(limited).hasStatus(429);
		assertThat(body(limited).get("code").asString()).isEqualTo("RATE_LIMITED");

		// Another tenant's agent has its own bucket, even though the tenant/agent ids are otherwise unrelated.
		ConversationTestData.Fixture otherTenant = this.data.seed();
		UUID otherConversation = this.data.start(this.conversations, otherTenant);
		assertThat(post("/api/v1/agent/conversations/" + otherConversation + "/messages", agentToken(otherTenant.tenantId()),
				Map.of("clientMessageId", "other-1", "body", "still works"))).hasStatus(201);
	}

	@Test
	void unknownConversationIs404ForParticipantsAnd403ForOthers() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ConversationTestData.Fixture otherTenant = this.data.seed();

		assertThat(get("/api/v1/agent/conversations/" + UUID.randomUUID(), agentToken(fixture.tenantId())))
			.hasStatus(404);
		MvcTestResult forbidden = get("/api/v1/agent/conversations/" + conversation, agentToken(otherTenant.tenantId()));
		assertThat(forbidden).hasStatus(403);
		assertThat(body(forbidden).get("code").asString()).isEqualTo("FORBIDDEN");
	}

	@Test
	void contactViewHidesAssignmentInternals() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.data.setAssignment(conversation, "ASSIGNED", "agentbob", null);
		String contactToken = this.sessionTokens
			.issueContact(fixture.tenantId(), fixture.contact().id(), fixture.inbox().id())
			.token();
		String agentToken = agentToken(fixture.tenantId());

		JsonNode contactView = body(get("/api/v1/widget/conversations/" + conversation, contactToken));
		JsonNode contactAssignment = contactView.get("assignment");
		assertThat(contactAssignment).isNotNull();
		assertThat(contactAssignment.size()).isEqualTo(2);
		assertThat(contactAssignment.has("state")).isTrue();
		assertThat(contactAssignment.has("agentId")).isTrue();
		assertThat(contactAssignment.has("decisionId")).isFalse();
		assertThat(contactAssignment.has("reason")).isFalse();
		assertThat(contactAssignment.has("explanation")).isFalse();
		assertThat(contactAssignment.get("state").asString()).isEqualTo("ASSIGNED");

		JsonNode agentAssignment = body(get("/api/v1/agent/conversations/" + conversation, agentToken))
			.get("assignment");
		assertThat(agentAssignment.size()).isEqualTo(5);
		assertThat(agentAssignment.has("decisionId")).isTrue();
		assertThat(agentAssignment.has("reason")).isTrue();
		assertThat(agentAssignment.has("explanation")).isTrue();

		assertThat(post("/api/v1/agent/conversations/" + conversation + "/resolve", agentToken, null)).hasStatusOk();
		JsonNode event = this.json.readTree(this.data.outboxPayloads(conversation, "conversation.updated").get(0));
		JsonNode eventAssignment = event.at("/data/conversation/assignment");
		assertThat(eventAssignment.size()).isEqualTo(2);
		assertThat(eventAssignment.has("state")).isTrue();
		assertThat(eventAssignment.has("agentId")).isTrue();
		// Resolve moved the assignment from ASSIGNED to RELEASE_PENDING, and the event carries that projection (R-P23).
		assertThat(eventAssignment.get("state").asString()).isEqualTo("RELEASE_PENDING");

		// A freshly started conversation carries both assignment keys, with agentId null (R-P29).
		ConversationTestData.Fixture fresh = this.data.seed();
		String freshToken = this.sessionTokens
			.issueContact(fresh.tenantId(), fresh.contact().id(), fresh.inbox().id())
			.token();
		JsonNode freshView = body(post("/api/v1/widget/conversations", freshToken, null));
		assertThat(freshView.at("/assignment/state").asString()).isEqualTo("REQUESTED");
		assertThat(freshView.at("/assignment/agentId").isNull()).isTrue();
		JsonNode created = this.json.readTree(this.data.outboxPayloads(UUID.fromString(freshView.get("id").asString()),
				"conversation.created").get(0));
		assertThat(created.at("/data/conversation/assignment/agentId").isNull()).isTrue();
		assertThat(created.at("/data/conversation/assignment").size()).isEqualTo(2);
	}

	@Test
	void assignWithoutIdempotencyKeyIs400() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);

		MvcTestResult result = post("/api/v1/agent/conversations/" + conversation + "/assign",
				agentToken(fixture.tenantId()), Map.of("mode", "CLAIM"));

		assertThat(result).hasStatus(400);
		assertThat(body(result).get("code").asString()).isEqualTo("VALIDATION_FAILED");
	}

	private String agentToken(String tenantId) {
		MvcTestResult login = post("/api/v1/agent/local-login", null, Map.of("agentId", "agentbob", "tenantId", tenantId));
		assertThat(login).hasStatus(200);
		return body(login).get("sessionToken").asString();
	}

	private MvcTestResult get(String uri, String token) {
		return this.mvc.get().uri(uri).header("Authorization", "Bearer " + token).exchange();
	}

	private MvcTestResult post(String uri, String token, Object payload) {
		var request = this.mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			request = request.header("Authorization", "Bearer " + token);
		}
		if (payload != null) {
			request = request.content(this.json.writeValueAsString(payload));
		}
		return request.exchange();
	}

	private MvcTestResult put(String uri, String token, Object payload) {
		return this.mvc.put()
			.uri(uri)
			.contentType(MediaType.APPLICATION_JSON)
			.header("Authorization", "Bearer " + token)
			.content(this.json.writeValueAsString(payload))
			.exchange();
	}

}
