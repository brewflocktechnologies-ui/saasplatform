package com.byteflock.chatplatform.conversation.internal;

import java.util.UUID;

import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ephemeral typing signal: 202 accepted, never persisted, limited to one per interval per principal (D12, D17). */
@RestController
class TypingController {

	private final TypingService typingService;

	TypingController(TypingService typingService) {
		this.typingService = typingService;
	}

	@PostMapping("/api/v1/widget/conversations/{conversationId}/typing")
	@RequiresPrincipal(PrincipalType.CONTACT)
	ResponseEntity<Void> widgetTyping(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) TypingRequest request) {
		return typing(caller, conversationId, request);
	}

	@PostMapping("/api/v1/agent/conversations/{conversationId}/typing")
	@RequiresPrincipal(PrincipalType.AGENT)
	ResponseEntity<Void> agentTyping(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) TypingRequest request) {
		return typing(caller, conversationId, request);
	}

	private ResponseEntity<Void> typing(Caller caller, UUID conversationId, TypingRequest request) {
		boolean typing = (request != null) && Boolean.TRUE.equals(request.typing());
		this.typingService.typing(caller, conversationId, typing);
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	record TypingRequest(Boolean typing) {
	}

}
