package com.byteflock.chatplatform.shared;

import io.micrometer.tracing.Tracer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps infrastructure and framework failures to RFC 9457 problem details without leaking exception messages, SQL or
 * stack traces. Every problem carries a {@code code} (status-derived for framework errors) and a {@code traceId}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final ObjectProvider<Tracer> tracer;

	ApiExceptionHandler(ObjectProvider<Tracer> tracer) {
		this.tracer = tracer;
	}

	@ExceptionHandler(DataAccessException.class)
	ProblemDetail databaseUnavailable(DataAccessException ex) {
		log.error("Database access failed", ex);
		return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "The database is unavailable.",
				this.tracer.getIfAvailable());
	}

	/**
	 * Framework-raised errors (malformed JSON, type mismatch, missing header or parameter, unknown route,
	 * {@code ResponseStatusException}) get a status-derived code here, because Boot's own handler would otherwise
	 * return a problem without one.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ProblemDetail problem = (body instanceof ProblemDetail detail) ? detail
				: ProblemDetail.forStatusAndDetail(statusCode, HttpStatus.resolve(statusCode.value()) != null
						? HttpStatus.valueOf(statusCode.value()).getReasonPhrase() : "Request failed");
		problem.setProperty(Problems.CODE, Problems.codeForStatus(statusCode.value()));
		String traceId = Problems.traceId(this.tracer.getIfAvailable());
		if (traceId != null) {
			problem.setProperty(Problems.TRACE_ID, traceId);
		}
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

}
