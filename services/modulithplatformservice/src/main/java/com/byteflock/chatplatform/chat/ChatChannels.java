package com.byteflock.chatplatform.chat;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Centrifugo channel names (namespace {@code chat}, see infra/centrifugo/config.json). */
final class ChatChannels {

	static final String INBOX = "chat:inbox";

	private static final String CONVERSATION_PREFIX = "chat:conversation:";

	private static final Pattern CONVERSATION = Pattern
		.compile("^chat:conversation:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

	private ChatChannels() {
	}

	static String conversation(UUID id) {
		return CONVERSATION_PREFIX + id;
	}

	/** Conversation id of a well-formed conversation channel, empty for anything else. */
	static Optional<UUID> conversationId(String channel) {
		Matcher m = CONVERSATION.matcher(channel == null ? "" : channel);
		return m.matches() ? Optional.of(UUID.fromString(m.group(1))) : Optional.empty();
	}

}
