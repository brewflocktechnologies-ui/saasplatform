package com.byteflock.chatplatform.health;

/** Status of one dependency. {@code detail} is a short, safe message (never an exception message or URL). */
public record DependencyStatus(String status, String detail) {

	static DependencyStatus up(String detail) {
		return new DependencyStatus("UP", detail);
	}

	static DependencyStatus down(String detail) {
		return new DependencyStatus("DOWN", detail);
	}

	boolean isUp() {
		return "UP".equals(this.status);
	}

}
