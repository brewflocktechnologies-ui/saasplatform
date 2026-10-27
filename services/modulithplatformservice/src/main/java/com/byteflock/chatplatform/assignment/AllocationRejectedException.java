package com.byteflock.chatplatform.assignment;

/** The allocation service answered a 4xx: the caller decides whether it is retryable, terminal or already done. */
public class AllocationRejectedException extends RuntimeException {

	private final int status;

	private final String code;

	public AllocationRejectedException(int status, String code) {
		super("allocation rejected the request with HTTP " + status + " " + code);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

}
