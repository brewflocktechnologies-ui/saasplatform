package com.byteflock.chatplatform.conversation;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The realtime channels of a conversation and of its inbox (D12). A subscription token is bound to exactly one of
 * these strings; the channel name alone never authorizes anything.
 */
public final class Channels {

	private static final String CONVERSATION_PREFIX = "chat:conversation:";

	private static final String INBOX_PREFIX = "chat:inbox:";

	/** Canonical channels use lowercase UUIDs; anything else is treated as malformed (400), not as another channel. */
	private static final Pattern CONVERSATION = Pattern.compile("^chat:conversation:([0-9a-f-]{36})$");

	private static final Pattern INBOX = Pattern.compile("^chat:inbox:([^:]+):([0-9a-f-]{36})$");

	private Channels() {
	}

	public static String conversation(UUID conversationId) {
		return CONVERSATION_PREFIX + conversationId;
	}

	public static String inbox(String tenantId, UUID inboxId) {
		return INBOX_PREFIX + tenantId + ":" + inboxId;
	}

	public static Optional<UUID> conversationId(String channel) {
		if (channel == null) {
			return Optional.empty();
		}
		Matcher matcher = CONVERSATION.matcher(channel);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		try {
			return Optional.of(UUID.fromString(matcher.group(1)));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public static Optional<InboxRef> inboxRef(String channel) {
		if (channel == null) {
			return Optional.empty();
		}
		Matcher matcher = INBOX.matcher(channel);
		if (!matcher.matches()) {
			return Optional.empty();
		}
		try {
			return Optional.of(new InboxRef(matcher.group(1), UUID.fromString(matcher.group(2))));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
