package com.byteflock.chatplatform.shared;

import java.io.IOException;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Returns the current trace id so a request seen in the browser can be found in Tempo/Loki. */
@Component
class TraceIdResponseFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Trace-Id";

	private final ObjectProvider<Tracer> tracer;

	TraceIdResponseFilter(ObjectProvider<Tracer> tracer) {
		this.tracer = tracer;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Tracer current = this.tracer.getIfAvailable();
		Span span = (current != null) ? current.currentSpan() : null;
		if (span != null) {
			response.setHeader(HEADER, span.context().traceId());
		}
		chain.doFilter(request, response);
	}

}
