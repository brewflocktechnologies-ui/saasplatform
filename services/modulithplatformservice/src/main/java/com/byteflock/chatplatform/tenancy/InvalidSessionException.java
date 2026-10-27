package com.byteflock.chatplatform.tenancy;

/**
 * Raised when a session token is missing, malformed, tampered with, expired or of the wrong kind. The message never
 * contains the token.
 */
public class InvalidSessionException extends RuntimeException {

	public InvalidSessionException(String reason) {
		super(reason);
	}

}
