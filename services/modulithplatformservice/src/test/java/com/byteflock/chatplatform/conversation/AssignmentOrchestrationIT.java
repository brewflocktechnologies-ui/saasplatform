package com.byteflock.chatplatform.conversation;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.internal.AssignmentWorker;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assignment worker against the allocation stub: dispatch and projection, queued polling, release through the
 * engine, unavailability backoff, rejection => FAILED, silent queued polls and the later sweep projecting a FAILED row
 * (R-P1, R-P13, R-P17, R-P23, R-P30).
 */
class AssignmentOrchestrationIT extends PlatformIntegrationTest {

	@Autowired
	AssignmentWorker worker;

	@Autowired
	ConversationService conversations;

	@Autowired
	TenantDirectory directory;

	ConversationTestData data;

	@BeforeEach
	void setUpData() {
		this.data = new ConversationTestData(this.directory, this.jdbc);
	}

	@Test
	void newConversationIsDispatchedAndProjected() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 404,
				"{\"code\":\"CONVERSATION_NOT_FOUND\"}");

		int processed = this.worker.runOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(ALLOCATION.count("POST", "/api/v1/conversations")).isEqualTo(1);
		assertThat(ALLOCATION.count("POST", "/api/v1/allocations/dispatch")).isEqualTo(1);
		assertThat(this.json.readTree(ALLOCATION.requests("POST", "/api/v1/allocations/dispatch").getFirst().body())
			.get("conversationId").asString()).isEqualTo(conversation.toString());
		assertThat(ALLOCATION.requests("POST", "/api/v1/allocations/dispatch").getFirst().header("Idempotency-Key"))
			.isEqualTo("assign:" + conversation + ":1");
		assertThat(this.data.assignmentState(conversation)).isEqualTo("ASSIGNED");
		assertThat(this.data.stateEventCount(conversation, "ASSIGNED")).isEqualTo(1);
		assertThat(this.data.outboxCount(conversation, "conversation.updated")).isEqualTo(1);
		String payload = this.data.outboxPayloads(conversation, "conversation.updated").get(0);
		assertThat(this.json.readTree(payload).at("/data/change").asString()).isEqualTo("ASSIGNED");
		assertThat(this.json.readTree(payload).at("/data/conversation/assignment").size()).isEqualTo(2);
		assertThat(this.json.readTree(payload).at("/data/conversation/assignment/agentId").asString())
			.isEqualTo("agentbob");
		assertThat(this.data.participantCount(conversation, "AGENT", "agentbob")).isEqualTo(1);
	}

	@Test
	void queuedOutcomeThenPollAssigns() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 404, "{}");
		ALLOCATION.respond("POST", "/api/v1/allocations/dispatch", 200,
				ALLOCATION.decision("QUEUED_NO_ELIGIBLE_AGENT", null));

		this.worker.runOnce();
		assertThat(this.data.assignmentState(conversation)).isEqualTo("QUEUED");
		assertThat(this.data.stateEventCount(conversation, "ASSIGNMENT_QUEUED")).isEqualTo(1);

		this.testClock.advance(Duration.ofSeconds(6));
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 200,
				"{\"status\":\"ASSIGNED\",\"assignedAgentId\":\"agentbob\"}");
		this.worker.runOnce();

		assertThat(this.data.assignmentState(conversation)).isEqualTo("ASSIGNED");
		assertThat(this.data.stateEventCount(conversation, "ASSIGNED")).isEqualTo(1);
	}

	@Test
	void resolveReleasesThroughAllocation() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 404, "{}");
		this.worker.runOnce();
		assertThat(this.data.assignmentState(conversation)).isEqualTo("ASSIGNED");

		this.conversations.resolve(fixture.agentCaller(), conversation, new CommandRequest(null, "done"));
		assertThat(this.data.assignmentState(conversation)).isEqualTo("RELEASE_PENDING");
		this.testClock.advance(Duration.ofSeconds(1));
		this.worker.runOnce();

		assertThat(ALLOCATION.count("POST", "/api/v1/conversations/" + conversation + "/resolve")).isEqualTo(1);
		assertThat(this.data.assignmentState(conversation)).isEqualTo("NONE");
		assertThat(this.jdbc.sql("SELECT assigned_agent_id FROM conversations WHERE id = :id")
			.param("id", conversation)
			.query(String.class)
			.optional()).isEmpty();
		assertThat(this.data.stateEventCount(conversation, "RELEASED")).isEqualTo(1);
	}

	@Test
	void allocationUnavailableKeepsRequestedWithBackoff() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respondUnavailable(true);

		this.worker.runOnce();

		assertThat(this.data.assignmentState(conversation)).isEqualTo("REQUESTED");
		assertThat(this.data.assignmentNextAttemptAt(conversation).toInstant())
			.isAfter(java.time.Instant.now());
		assertThat(this.data.outboxCount(conversation, "conversation.updated")).isZero();
	}

	@Test
	void rejectedOutcomeIsFailedWithReason() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 404, "{}");
		ALLOCATION.respond("POST", "/api/v1/allocations/dispatch", 200,
				ALLOCATION.decision("REJECTED_INVALID_INPUT", null));

		this.worker.runOnce();

		assertThat(this.data.assignmentState(conversation)).isEqualTo("FAILED");
		assertThat(this.data.stateEventCount(conversation, "ASSIGNMENT_FAILED")).isEqualTo(1);
		assertThat(this.data.outboxPayloads(conversation, "conversation.updated").get(0)).contains("ASSIGNMENT_FAILED");
	}

	@Test
	void queuedPollIsSilent() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 404, "{}");
		ALLOCATION.respond("POST", "/api/v1/allocations/dispatch", 200,
				ALLOCATION.decision("QUEUED_NO_ELIGIBLE_AGENT", null));
		this.worker.runOnce();
		long version = this.data.version(conversation);
		int outbox = this.data.outboxCount(conversation, "conversation.updated");

		this.testClock.advance(Duration.ofSeconds(6));
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 200,
				"{\"status\":\"QUEUED\",\"assignedAgentId\":null}");
		this.worker.runOnce();
		this.testClock.advance(Duration.ofSeconds(6));
		this.worker.runOnce();

		assertThat(this.data.version(conversation)).isEqualTo(version);
		assertThat(this.data.outboxCount(conversation, "conversation.updated")).isEqualTo(outbox);
		assertThat(ALLOCATION.count("POST", "/api/v1/allocations/dispatch")).isEqualTo(1);
	}

	@Test
	void failedRowProjectsLaterSweepAssignment() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = this.data.start(this.conversations, fixture);
		this.jdbc.sql("""
				UPDATE conversations SET assignment_state = 'FAILED', assignment_reason = 'TEST',
				       assignment_next_attempt_at = now() + interval '60 seconds'
				WHERE id = :id
				""").param("id", conversation).update();
		ALLOCATION.respond("GET", "/api/v1/conversations/" + conversation, 200,
				"{\"status\":\"ASSIGNED\",\"assignedAgentId\":\"agentbob\"}");

		this.worker.runOnce();
		assertThat(this.data.assignmentState(conversation)).isEqualTo("FAILED");

		this.testClock.advance(Duration.ofSeconds(61));
		this.worker.runOnce();

		assertThat(this.data.assignmentState(conversation)).isEqualTo("ASSIGNED");
		assertThat(this.data.stateEventCount(conversation, "ASSIGNED")).isEqualTo(1);
	}

}
