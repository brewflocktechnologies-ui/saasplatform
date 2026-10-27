package com.byteflock.chatplatform.conversation.internal;

import java.util.UUID;

import com.byteflock.chatplatform.conversation.CommandRequest;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.conversation.MessagePage;
import com.byteflock.chatplatform.conversation.MessageView;
import com.byteflock.chatplatform.conversation.ReadRequest;
import com.byteflock.chatplatform.conversation.SendMessageRequest;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RateLimiter;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Widget conversation API. A visitor reaches only its own conversation of its own inbox; the response assignment is the
 * reduced {@code {state, agentId}} view (R-P29). Sending is rate limited at the HTTP boundary, per principal of a tenant
 * (R-P31 M3), so service-level tests are not throttled.
 */
@RestController
@RequestMapping("/api/v1/widget/conversations")
class WidgetConversationController {

	private final ConversationService conversations;

	private final MessageService messages;

	private final HistoryService history;

	private final ReadCursorService readCursors;

	private final RateLimiter rateLimiter;

	private final int messagesPerMinute;

	WidgetConversationController(ConversationService conversations, MessageService messages, HistoryService history,
			ReadCursorService readCursors, RateLimiter rateLimiter,
			@Value("${app.rate-limits.messages-per-minute}") int messagesPerMinute) {
		this.conversations = conversations;
		this.messages = messages;
		this.history = history;
		this.readCursors = readCursors;
		this.rateLimiter = rateLimiter;
		this.messagesPerMinute = messagesPerMinute;
	}

	@PostMapping
	@RequiresPrincipal(PrincipalType.CONTACT)
	ResponseEntity<ConversationView> start(Caller caller) {
		ConversationService.StartResult result = this.conversations.startOrResume(caller);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(result.conversation());
	}

	@GetMapping("/{conversationId}")
	@RequiresPrincipal(PrincipalType.CONTACT)
	ConversationView get(Caller caller, @PathVariable UUID conversationId) {
		return this.conversations.get(caller, conversationId);
	}

	@GetMapping("/{conversationId}/messages")
	@RequiresPrincipal(PrincipalType.CONTACT)
	MessagePage messages(Caller caller, @PathVariable UUID conversationId,
			@RequestParam(name = "before", required = false) Long before,
			@RequestParam(name = "after", required = false) Long after,
			@RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
		return this.history.page(caller, conversationId, before, after, limit);
	}

	@PostMapping("/{conversationId}/messages")
	@RequiresPrincipal(PrincipalType.CONTACT)
	ResponseEntity<MessageView> send(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) SendMessageRequest request) {
		enforceMessageLimit(caller);
		MessageService.SendResult result = this.messages.send(caller, conversationId, request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
	}

	@PutMapping("/{conversationId}/read")
	@RequiresPrincipal(PrincipalType.CONTACT)
	ResponseEntity<Void> read(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) ReadRequest request) {
		if (request == null || request.lastReadSequence() == null) {
			throw new IllegalArgumentException("lastReadSequence is required");
		}
		this.readCursors.advance(caller, conversationId, request.lastReadSequence());
		return ResponseEntity.noContent().build();
	}

	private void enforceMessageLimit(Caller caller) {
		String bucket = "msg:" + caller.tenantId() + ":" + caller.type().name() + ":" + caller.principalId();
		if (!this.rateLimiter.tryAcquire(bucket, this.messagesPerMinute)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
		}
	}

}
