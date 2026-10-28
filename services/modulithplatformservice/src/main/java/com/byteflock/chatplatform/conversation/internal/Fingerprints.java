package com.byteflock.chatplatform.conversation.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Request fingerprint of a message body ({@code sha256(trimmed body)}): the same {@code clientMessageId} with the same
 * body is a replay, with a different body it is {@code CLIENT_MESSAGE_ID_REUSED} (D5).
 */
final class Fingerprints {

	private Fingerprints() {
	}

	static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
