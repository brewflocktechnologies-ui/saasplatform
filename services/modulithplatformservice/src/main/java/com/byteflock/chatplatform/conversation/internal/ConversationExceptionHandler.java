package com.byteflock.chatplatform.conversation.internal;

import com.byteflock.chatplatform.assignment.AllocationRejectedException;
import com.byteflock.chatplatform.assignment.AllocationUnavailableException;
import com.byteflock.chatplatform.conversation.ConversationAccessDeniedException;
import com.byteflock.chatplatform.conversation.ConversationConflictException;
import com.byteflock.chatplatform.conversation.ConversationNotFoundException;
import com.byteflock.chatplatform.shared.Problems;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps conversation errors to RFC 9457 problems with the documented {@code code} (overview §5): 404 {@code NOT_FOUND},
 * 403 {@code FORBIDDEN}, 409 with the conflict's own code, 400 {@code VALIDATION_FAILED} for request validation
 * (there is no bean-validation starter, validation is explicit code in the services).
 */
@RestControllerAdvice
class ConversationExceptionHandler {

	private final ObjectProvider<Tracer> tracer;

	private final MeterRegistry meters;

	ConversationExceptionHandler(ObjectProvider<Tracer> tracer, MeterRegistry meters) {
		this.tracer = tracer;
		this.meters = meters;
	}

	@ExceptionHandler(AllocationRejectedException.class)
	ProblemDetail allocationRejected(AllocationRejectedException ex) {
		return Problems.of(HttpStatus.CONFLICT, "ALLOCATION_REJECTED", ex.code(), this.tracer.getIfAvailable());
	}

	@ExceptionHandler(AllocationUnavailableException.class)
	ProblemDetail allocationUnavailable(AllocationUnavailableException ex) {
		return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "ALLOCATION_UNAVAILABLE",
				"The allocation service is unavailable.", this.tracer.getIfAvailable());
	}
	@ExceptionHandler(ConversationNotFoundException.class)
	ProblemDetail notFound(ConversationNotFoundException ex) {
		return Problems.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "The conversation does not exist.",
				this.tracer.getIfAvailable());
	}

	@ExceptionHandler(ConversationAccessDeniedException.class)
	ProblemDetail forbidden(ConversationAccessDeniedException ex) {
		return Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "The conversation is not visible to this caller.",
				this.tracer.getIfAvailable());
	}

	@ExceptionHandler(ConversationConflictException.class)
	ProblemDetail conflict(ConversationConflictException ex) {
		this.meters.counter("api.problems", "code", ex.code()).increment();
		return Problems.of(HttpStatus.CONFLICT, ex.code(), detailFor(ex.code()), this.tracer.getIfAvailable());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail validation(IllegalArgumentException ex) {
		// The message is our own validation text and never contains SQL, tokens or stack traces.
		return Problems.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), this.tracer.getIfAvailable());
	}

	private static String detailFor(String code) {
		return switch (code) {
			case "CLIENT_MESSAGE_ID_REUSED" -> "The clientMessageId was already used with a different body.";
			case "CONVERSATION_RESOLVED" -> "The conversation is resolved; reopen it before replying as an agent.";
			case "CONVERSATION_ARCHIVED" -> "The conversation is archived; start a new conversation instead.";
			case "INVALID_TRANSITION" -> "The command is not allowed from the conversation's current state.";
			case "STALE_VERSION" -> "The conversation changed since the caller's expected version.";
			case "CONVERSATION_ACTIVE_EXISTS" -> "The contact already has an active conversation in this inbox.";
			default -> "The command conflicts with the conversation's current state.";
		};
	}

}
