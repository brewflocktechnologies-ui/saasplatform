package com.byteflock.chatplatform.shared;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 9457 problem details with the platform's stable {@code code} property and the current trace id, so every
 * error response is machine-readable and correlatable (master spec 6, overview §5).
 */
public final class Problems {

	/** Stable, machine-readable problem code (required on every problem response). */
	public static final String CODE = "code";

	/** Correlation id, identical to the {@code X-Trace-Id} response header. */
	public static final String TRACE_ID = "traceId";

	private Problems() {
	}

	public static ProblemDetail of(HttpStatus status, String code, String detail) {
		return of(status, code, detail, null);
	}

	public static ProblemDetail of(HttpStatus status, String code, String detail, Tracer tracer) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty(CODE, code);
		String traceId = traceId(tracer);
		if (traceId != null) {
			problem.setProperty(TRACE_ID, traceId);
		}
		return problem;
	}

	/**
	 * Status-derived code for errors raised by the framework rather than by our code (malformed JSON, missing header,
	 * unknown route, {@code ResponseStatusException}): 400 VALIDATION_FAILED, 401 UNAUTHENTICATED, 403 FORBIDDEN,
	 * 404 NOT_FOUND, 429 RATE_LIMITED, otherwise the upper-snake-case reason phrase (e.g. METHOD_NOT_ALLOWED).
	 */
	public static String codeForStatus(int status) {
		return switch (status) {
			case 400 -> "VALIDATION_FAILED";
			case 401 -> "UNAUTHENTICATED";
			case 403 -> "FORBIDDEN";
			case 404 -> "NOT_FOUND";
			case 429 -> "RATE_LIMITED";
			default -> {
				HttpStatus resolved = HttpStatus.resolve(status);
				String reason = (resolved != null) ? resolved.getReasonPhrase() : "ERROR";
				yield reason.toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
			}
		};
	}

	public static String traceId(Tracer tracer) {
		if (tracer == null) {
			return null;
		}
		Span span = tracer.currentSpan();
		return (span != null) ? span.context().traceId() : null;
	}

}
