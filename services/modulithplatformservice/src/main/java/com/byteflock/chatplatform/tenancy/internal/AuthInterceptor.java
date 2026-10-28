package com.byteflock.chatplatform.tenancy.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.InvalidSessionException;
import com.byteflock.chatplatform.tenancy.LocalDevProperties;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the caller for handlers annotated with {@link RequiresPrincipal} and stores it as a request attribute.
 * <p>
 * Handlers without the annotation (widget session start, agent local login, realtime health) pass through, as do CORS
 * pre-flight {@code OPTIONS} requests. A failure is 401 {@code UNAUTHENTICATED} (missing, invalid or expired token) or
 * 403 {@code FORBIDDEN} (valid token of another principal type) — never a redirect, never a token in the response.
 */
class AuthInterceptor implements HandlerInterceptor {

	static final String CALLER_ATTRIBUTE = Caller.class.getName();

	static final String MDC_TENANT = "tenantId";

	private static final String AUTHORIZATION = "Authorization";

	private static final String BEARER = "Bearer ";

	private static final String ADMIN_HEADER = "X-Admin-Token";

	private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

	private final SessionTokens sessionTokens;

	private final LocalDevProperties localDev;

	AuthInterceptor(SessionTokens sessionTokens, LocalDevProperties localDev) {
		this.sessionTokens = sessionTokens;
		this.localDev = localDev;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		RequiresPrincipal required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(),
				RequiresPrincipal.class);
		if (required == null) {
			required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresPrincipal.class);
		}
		if (required == null) {
			return true;
		}
		Caller caller = (required.value() == PrincipalType.ADMIN) ? admin(request) : session(request, required.value());
		request.setAttribute(CALLER_ATTRIBUTE, caller);
		if (caller.tenantId() != null) {
			MDC.put(MDC_TENANT, caller.tenantId());
		}
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		// Never leak one request's tenant into the next request handled by the same thread.
		MDC.remove(MDC_TENANT);
	}

	private Caller admin(HttpServletRequest request) {
		// LOCAL POC — NOT REAL AUTHENTICATION: a shared local-dev token, compared in constant time (R-P11).
		String configured = this.localDev.adminToken();
		String provided = request.getHeader(ADMIN_HEADER);
		if (!this.localDev.enabled() || configured.isBlank() || provided == null
				|| !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
						provided.getBytes(StandardCharsets.UTF_8))) {
			log.debug("Rejected admin request without a valid X-Admin-Token");
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return Caller.admin();
	}

	private Caller session(HttpServletRequest request, PrincipalType required) {
		String header = request.getHeader(AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		Caller caller;
		try {
			caller = this.sessionTokens.verify(header.substring(BEARER.length()).trim());
		}
		catch (InvalidSessionException ex) {
			log.debug("Rejected session token: {}", ex.getMessage());
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		if (caller.type() != required) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		}
		return caller;
	}

	/** Used by the caller argument resolver to hand the caller to a controller method. */
	static Caller caller(HttpServletRequest request) {
		Object caller = request.getAttribute(CALLER_ATTRIBUTE);
		if (caller instanceof Caller value) {
			return value;
		}
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
	}

}
