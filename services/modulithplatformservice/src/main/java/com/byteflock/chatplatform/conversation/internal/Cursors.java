package com.byteflock.chatplatform.conversation.internal;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque inbox page cursor: base64url of {@code <activityAt>|<id>}, the keyset position (activity descending, id
 * descending). Opaque so the sort order can change without breaking clients; never trusted for authorization.
 */
final class Cursors {

	private Cursors() {
	}

	static String encode(OffsetDateTime activityAt, UUID id) {
		String raw = activityAt.toInstant() + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	static Optional<InboxCursor> decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return Optional.empty();
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int separator = raw.indexOf('|');
			if (separator < 0) {
				return Optional.empty();
			}
			return Optional.of(new InboxCursor(OffsetDateTime.parse(raw.substring(0, separator)),
					UUID.fromString(raw.substring(separator + 1))));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
