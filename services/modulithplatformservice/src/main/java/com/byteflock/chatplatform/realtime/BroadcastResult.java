package com.byteflock.chatplatform.realtime;

/**
 * Outcome of one Centrifugo broadcast call (Plan 01 ruling R-P30).
 *
 * @param kind whether the publication was accepted, failed for a transport reason, or was rejected by Centrifugo
 * @param error bounded error identity — an exception class name, `HTTP &lt;status&gt;` or a Centrifugo error code.
 * Never payload text, never a token.
 */
public record BroadcastResult(Kind kind, String error) {

	public enum Kind {

		/** Centrifugo accepted every channel. */
		PUBLISHED,

		/** Connection refused, timeout, HTTP 5xx or a `temporary: true` error: retry, never park. */
		TRANSPORT_FAILURE,

		/** A non-temporary Centrifugo error object or HTTP 4xx: the outbox may park the row. */
		REJECTED

	}

	public static BroadcastResult published() {
		return new BroadcastResult(Kind.PUBLISHED, null);
	}

	public static BroadcastResult transportFailure(String error) {
		return new BroadcastResult(Kind.TRANSPORT_FAILURE, error);
	}

	public static BroadcastResult rejected(String error) {
		return new BroadcastResult(Kind.REJECTED, error);
	}

	public boolean isPublished() {
		return this.kind == Kind.PUBLISHED;
	}

}
