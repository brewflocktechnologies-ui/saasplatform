package com.byteflock.chatplatform.chat;

import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The only two identities of the local chat MVP. LOCAL POC — NOT REAL AUTHENTICATION: the role is derived here on
 * the server, never taken from the client. A real identity resolver must replace this before UAT/production.
 */
enum DemoActor {

	USERONE("userone", "VISITOR"), AGENTBOB("agentbob", "AGENT");

	final String id;

	final String role;

	DemoActor(String id, String role) {
		this.id = id;
		this.role = role;
	}

	boolean isAgent() {
		return this == AGENTBOB;
	}

	/** @throws ResponseStatusException 400 for a missing or unsupported actor (no fallback identity) */
	static DemoActor resolve(String actorId) {
		return Arrays.stream(values())
			.filter((a) -> a.id.equals(actorId))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"actorId must be one of the demo actors: userone, agentbob"));
	}

}
