package com.byteflock.chatplatform.outbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.byteflock.chatplatform.PlatformIntegrationTest;
import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.ConversationTestData;
import com.byteflock.chatplatform.conversation.CommandRequest;
import com.byteflock.chatplatform.conversation.ConversationChange;
import com.byteflock.chatplatform.conversation.SendMessageRequest;
import com.byteflock.chatplatform.conversation.internal.ConversationService;
import com.byteflock.chatplatform.conversation.internal.MessageService;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer-side event contract (R14, R15, R-P3, R-P23, R-P29): real outbox payloads contain every key their schema
 * requires, the event type matches the schema's {@code const}, the event assignment has exactly {@code state} and
 * {@code agentId}, and the change enum covers every value the platform emits.
 */
class EventEnvelopeContractTest extends PlatformIntegrationTest {

	static final Path SCHEMAS = Path.of("../../libs/contracts/json-schema/chat");

	@Autowired
	ConversationService conversations;

	@Autowired
	MessageService messages;

	@Autowired
	TenantDirectory directory;

	@Test
	void everyPublishedEnvelopeMatchesItsSchemaShape() throws Exception {
		ConversationTestDataHolder holder = seed();

		assertEnvelope("conversation-created.v1.schema.json", lastPayload(holder.conversation(), "conversation.created"));
		this.messages.send(holder.contact(), holder.conversation(),
				new SendMessageRequest("contract-1", "hello"));
		assertEnvelope("message-created.v1.schema.json", lastPayload(holder.conversation(), "message.created"));
		this.conversations.resolve(holder.agent(), holder.conversation(),
				new CommandRequest(null, "done"));
		assertEnvelope("conversation-updated.v1.schema.json", lastPayload(holder.conversation(), "conversation.updated"));
	}

	@Test
	void conversationEventAssignmentHasExactlyStateAndAgentId() throws Exception {
		ConversationTestDataHolder holder = seed();
		JsonNode created = lastPayload(holder.conversation(), "conversation.created");
		assertThat(created.at("/data/conversation/assignment").size()).isEqualTo(2);
		assertThat(created.at("/data/conversation/assignment/agentId").isNull()).isTrue();

		this.jdbc.sql("""
				UPDATE conversations SET assignment_state = 'ASSIGNED', assigned_agent_id = 'agentbob',
				       assignment_decision_id = uuidv7(), assignment_reason = 'TEST', assignment_explanation = 'test'
				WHERE id = :id
				""").param("id", holder.conversation()).update();
		this.conversations.resolve(holder.agent(), holder.conversation(), new CommandRequest(null, null));

		JsonNode updated = lastPayload(holder.conversation(), "conversation.updated");
		JsonNode assignment = updated.at("/data/conversation/assignment");
		assertThat(assignment.size()).isEqualTo(2);
		assertThat(assignment.has("state")).isTrue();
		assertThat(assignment.has("agentId")).isTrue();
		assertThat(assignment.get("agentId").asString()).isEqualTo("agentbob");
	}

	@Test
	void changeEnumCoversEveryEmittedValue() throws Exception {
		JsonNode schema = schema("conversation-updated.v1.schema.json");
		JsonNode enumValues = schema.at("/properties/data/properties/change/enum");
		for (ConversationChange change : ConversationChange.values()) {
			assertThat(enumValues.toString()).contains(change.name());
		}
		assertThat(AssignmentState.values().length).isEqualTo(7);
	}

	private void assertEnvelope(String schemaFile, JsonNode payload) throws IOException {
		JsonNode schema = schema(schemaFile);
		for (JsonNode required : schema.get("required")) {
			assertThat(payload.has(required.asString())).as("%s requires %s", schemaFile, required.asString())
				.isTrue();
		}
		assertThat(payload.get("eventType").asString())
			.isEqualTo(schema.at("/properties/eventType/const").asString());
		assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
	}

	private JsonNode schema(String file) throws IOException {
		return this.json.readTree(Files.readString(SCHEMAS.resolve(file)));
	}

	private JsonNode lastPayload(UUID conversation, String eventType) {
		String payload = this.jdbc.sql("""
				SELECT payload FROM outbox_events WHERE aggregate_id = :id AND event_type = :type
				ORDER BY created_at DESC LIMIT 1
				""")
			.param("id", conversation.toString())
			.param("type", eventType)
			.query(String.class)
			.single();
		return this.json.readTree(payload);
	}

	private ConversationTestDataHolder seed() {
		ConversationTestData data = new ConversationTestData(this.directory, this.jdbc);
		ConversationTestData.Fixture fixture = data.seed();
		UUID conversation = data.start(this.conversations, fixture);
		return new ConversationTestDataHolder(conversation, fixture.contactCaller(), fixture.agentCaller());
	}

	record ConversationTestDataHolder(UUID conversation, com.byteflock.chatplatform.tenancy.Caller contact,
			com.byteflock.chatplatform.tenancy.Caller agent) {
	}

}
