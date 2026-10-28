package com.byteflock.chatplatform.retention;

import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.ConversationTestData;
import com.byteflock.chatplatform.conversation.SendMessageRequest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** Retention admin API: token protection, HTTP dry run, run lookup, policy update with legal hold. */
class AdminApiIT extends PlatformIntegrationTest {

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
	void purgeRequiresAdminToken() {
		assertThat(this.mvc.post()
			.uri("/api/v1/admin/retention/purge")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"tenantId\":\"tenant-x\",\"dryRun\":true}")
			.exchange()).hasStatus(401);
	}

	@Test
	void purgeDryRunViaHttpChangesNothing() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = oldResolvedConversation(fixture);

		MvcTestResult result = this.mvc.post()
			.uri("/api/v1/admin/retention/purge")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Admin-Token", ADMIN_TOKEN)
			.content("{\"tenantId\":\"" + fixture.tenantId() + "\",\"dryRun\":true}")
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(body(result).get("status").asString()).isEqualTo("COMPLETED");
		assertThat(body(result).get("messagesDeleted").asInt()).isEqualTo(1);
		assertThat(this.data.messageCount(conversation)).isEqualTo(1);
		assertThat(body(result).get("id").asString()).isNotBlank();
	}

	@Test
	void runByIdReturnsRunAndUnknownIs404() {
		ConversationTestData.Fixture fixture = this.data.seed();
		oldResolvedConversation(fixture);
		MvcTestResult purge = this.mvc.post()
			.uri("/api/v1/admin/retention/purge")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Admin-Token", ADMIN_TOKEN)
			.content("{\"tenantId\":\"" + fixture.tenantId() + "\",\"dryRun\":true}")
			.exchange();
		String runId = body(purge).get("id").asString();

		assertThat(this.mvc.get()
			.uri("/api/v1/admin/retention/runs/" + runId)
			.header("X-Admin-Token", ADMIN_TOKEN)
			.exchange()).hasStatusOk();
		assertThat(this.mvc.get()
			.uri("/api/v1/admin/retention/runs/" + UUID.randomUUID())
			.header("X-Admin-Token", ADMIN_TOKEN)
			.exchange()).hasStatus(404);
	}

	@Test
	void policyPutWithLegalHoldStopsPurge() {
		ConversationTestData.Fixture fixture = this.data.seed();
		UUID conversation = oldResolvedConversation(fixture);
		MvcTestResult policy = this.mvc.put()
			.uri("/api/v1/admin/retention/policies/" + fixture.tenantId())
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Admin-Token", ADMIN_TOKEN)
			.content("{\"resolvedRetentionDays\":30,\"legalHold\":true}")
			.exchange();
		assertThat(policy).hasStatusOk();
		assertThat(body(policy).get("legalHold").asBoolean()).isTrue();

		MvcTestResult purge = this.mvc.post()
			.uri("/api/v1/admin/retention/purge")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Admin-Token", ADMIN_TOKEN)
			.content("{\"tenantId\":\"" + fixture.tenantId() + "\",\"dryRun\":false}")
			.exchange();

		assertThat(purge).hasStatusOk();
		assertThat(body(purge).get("conversationsExamined").asInt()).isZero();
		assertThat(this.data.messageCount(conversation)).isEqualTo(1);
	}

	private UUID oldResolvedConversation(ConversationTestData.Fixture fixture) {
		var contact = this.directory.ensureContact(fixture.tenantId(), "admin-" + UUID.randomUUID(), "Old");
		Caller caller = new Caller(fixture.tenantId(), PrincipalType.CONTACT, contact.id().toString(),
				fixture.inbox().id());
		UUID conversation = this.conversations.startOrResume(caller).conversation().id();
		this.messages.send(caller, conversation, new SendMessageRequest("admin-1", "old"));
		this.jdbc.sql("UPDATE conversations SET status = 'RESOLVED', resolved_at = now() - interval '40 days' WHERE id = :id")
			.param("id", conversation)
			.update();
		return conversation;
	}

}
