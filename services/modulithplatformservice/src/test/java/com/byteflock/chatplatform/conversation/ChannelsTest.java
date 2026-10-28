package com.byteflock.chatplatform.conversation;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Channel name parsing (D12): canonical lowercase UUIDs only, no injection, no ambiguous segments. */
class ChannelsTest {

	static final UUID CONVERSATION = UUID.fromString("019a1b2c-0000-7000-8000-000000000001");

	static final UUID INBOX = UUID.fromString("019a1b2c-0000-7000-8000-000000000002");

	@Test
	void conversationChannelRoundTrips() {
		String channel = Channels.conversation(CONVERSATION);

		assertThat(channel).isEqualTo("chat:conversation:" + CONVERSATION);
		assertThat(Channels.conversationId(channel)).contains(CONVERSATION);
		assertThat(Channels.inboxRef(channel)).isEmpty();
	}

	@Test
	void inboxChannelRoundTrips() {
		String channel = Channels.inbox("tenant-local", INBOX);

		assertThat(channel).isEqualTo("chat:inbox:tenant-local:" + INBOX);
		assertThat(Channels.inboxRef(channel)).contains(new InboxRef("tenant-local", INBOX));
		assertThat(Channels.conversationId(channel)).isEmpty();
	}

	@Test
	void rejectsMalformedChannels() {
		assertThat(Channels.conversationId(null)).isEmpty();
		assertThat(Channels.conversationId("chat:conversation:not-a-uuid")).isEmpty();
		assertThat(Channels.conversationId("chat:conversation:" + CONVERSATION + ":extra")).isEmpty();
		assertThat(Channels.inboxRef("chat:inbox:a:b:c")).isEmpty();
		assertThat(Channels.inboxRef("chat:inbox:" + INBOX)).isEmpty();
		assertThat(Channels.inboxRef("chat:inbox:tenant:../../etc")).isEmpty();
	}

	@Test
	void rejectsUppercaseUuids() {
		// Canonical channels are lowercase; an uppercase id is a different (malformed) channel, never a lookup alias.
		assertThat(Channels.conversationId("chat:conversation:" + CONVERSATION.toString().toUpperCase())).isEmpty();
		assertThat(Channels.inboxRef("chat:inbox:tenant-local:" + INBOX.toString().toUpperCase())).isEmpty();
	}

}
