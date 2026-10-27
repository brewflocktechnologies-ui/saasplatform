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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent conversation API: read, reply, read cursors and the lifecycle commands. Assignment routes work but never gates
 * sending (D14). The full {@code AssignmentView} is returned to agents only (R-P29).
 */
@RestController
@RequestMapping("/api/v1/agent/conversations")
class AgentConversationController {

	private final ConversationService conversations;

	private final MessageService messages;

	private final HistoryService history;

	private final ReadCursorService readCursors;

	private final RateLimiter rateLimiter;

	private final int messagesPerMinute;

	AgentConversationController(ConversationService conversations, MessageService messages, HistoryService history,
			ReadCursorService readCursors, RateLimiter rateLimiter,
			@Value("${app.rate-limits.messages-per-minute}") int messagesPerMinute) {
		this.conversations = conversations;
		this.messages = messages;
		this.history = history;
		this.readCursors = readCursors;
		this.rateLimiter = rateLimiter;
		this.messagesPerMinute = messagesPerMinute;
	}

	@GetMapping("/{conversationId}")
	@RequiresPrincipal(PrincipalType.AGENT)
	ConversationView get(Caller caller, @PathVariable UUID conversationId) {
		return this.conversations.get(caller, conversationId);
	}

	@GetMapping("/{conversationId}/messages")
	@RequiresPrincipal(PrincipalType.AGENT)
	MessagePage messages(Caller caller, @PathVariable UUID conversationId,
			@RequestParam(name = "before", required = false) Long before,
			@RequestParam(name = "after", required = false) Long after,
			@RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
		return this.history.page(caller, conversationId, before, after, limit);
	}

	@PostMapping("/{conversationId}/messages")
	@RequiresPrincipal(PrincipalType.AGENT)
	ResponseEntity<MessageView> send(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) SendMessageRequest request) {
		enforceMessageLimit(caller);
		MessageService.SendResult result = this.messages.send(caller, conversationId, request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
	}

	@PutMapping("/{conversationId}/read")
	@RequiresPrincipal(PrincipalType.AGENT)
	ResponseEntity<Void> read(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) ReadRequest request) {
		if (request == null || request.lastReadSequence() == null) {
			throw new IllegalArgumentException("lastReadSequence is required");
		}
		this.readCursors.advance(caller, conversationId, request.lastReadSequence());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{conversationId}/resolve")
	@RequiresPrincipal(PrincipalType.AGENT)
	ConversationView resolve(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) CommandRequest request) {
		return this.conversations.resolve(caller, conversationId, request);
	}

	@PostMapping("/{conversationId}/reopen")
	@RequiresPrincipal(PrincipalType.AGENT)
	ConversationView reopen(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) CommandRequest request) {
		return this.conversations.reopen(caller, conversationId, request);
	}

	@PostMapping("/{conversationId}/pending")
	@RequiresPrincipal(PrincipalType.AGENT)
	ConversationView pending(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) CommandRequest request) {
		return this.conversations.markPending(caller, conversationId, request);
	}

	@PostMapping("/{conversationId}/archive")
	@RequiresPrincipal(PrincipalType.AGENT)
	ConversationView archive(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) CommandRequest request) {
		return this.conversations.archive(caller, conversationId, request);
	}

	@PostMapping("/{conversationId}/unarchive")
	@RequiresPrincipal(PrincipalType.AGENT)
	ConversationView unarchive(Caller caller, @PathVariable UUID conversationId,
			@RequestBody(required = false) CommandRequest request) {
		return this.conversations.unarchive(caller, conversationId, request);
	}

	/** Assignment is implemented by Plan 04; until then the route answers 501 {@code NOT_IMPLEMENTED}. */
	@PostMapping("/{conversationId}/assign")
	@RequiresPrincipal(PrincipalType.AGENT)
	ResponseEntity<Void> assign(Caller caller, @PathVariable UUID conversationId) {
		throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
	}

	private void enforceMessageLimit(Caller caller) {
		String bucket = "msg:" + caller.tenantId() + ":" + caller.type().name() + ":" + caller.principalId();
		if (!this.rateLimiter.tryAcquire(bucket, this.messagesPerMinute)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
		}
	}

}
