package com.byteflock.chatplatform.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps infrastructure failures to RFC 9457 problem details without leaking exception messages or stack traces. */
@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(DataAccessException.class)
	ProblemDetail databaseUnavailable(DataAccessException ex) {
		log.error("Database access failed", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The database is unavailable.");
		problem.setTitle("Service Unavailable");
		return problem;
	}

}
