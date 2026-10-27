package com.byteflock.chatplatform.assignment;

/** The allocation service could not be reached, timed out or answered 5xx: transient, retry with backoff. */
public class AllocationUnavailableException extends RuntimeException {

	public AllocationUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
