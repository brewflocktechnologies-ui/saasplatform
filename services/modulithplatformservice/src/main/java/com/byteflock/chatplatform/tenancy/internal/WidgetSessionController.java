package com.byteflock.chatplatform.tenancy.internal;

import java.util.UUID;

import com.byteflock.chatplatform.tenancy.Contact;
import com.byteflock.chatplatform.tenancy.Inbox;
import com.byteflock.chatplatform.tenancy.IssuedSession;
import com.byteflock.chatplatform.tenancy.RefreshableContact;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import com.byteflock.chatplatform.tenancy.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public widget session endpoint: a public {@code widgetKey} selects the inbox (and thereby the tenant), and the response
 * is a contact session token. A stored (possibly expired) token refreshes the same contact for as long as the absolute
 * refresh window allows.
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: the widget key identifies an inbox, it does not authenticate a person; production
 * replaces it with verified widget identity (ADR-0008).
 */
@RestController
@RequestMapping("/api/v1/widget/sessions")
class WidgetSessionController {

	private final TenantDirectory directory;

	private final SessionTokens sessionTokens;

	private final RateLimiter rateLimiter;

	private final int sessionsPerMinute;

	WidgetSessionController(TenantDirectory directory, SessionTokens sessionTokens, RateLimiter rateLimiter,
			@Value("${app.rate-limits.sessions-per-minute}") int sessionsPerMinute) {
		this.directory = directory;
		this.sessionTokens = sessionTokens;
		this.rateLimiter = rateLimiter;
		this.sessionsPerMinute = sessionsPerMinute;
	}

	@PostMapping
	ResponseEntity<WidgetSessionResponse> create(@RequestBody(required = false) WidgetSessionRequest request,
			HttpServletRequest http) {
		if (request == null || request.widgetKey() == null || request.widgetKey().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		// Master spec 8: sessions are rate limited per widget key and client address (D17).
		if (!this.rateLimiter.tryAcquire("sessions:" + request.widgetKey() + ":" + http.getRemoteAddr(),
				this.sessionsPerMinute)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
		}
		if (request.sessionToken() != null && !request.sessionToken().isBlank()) {
			return ResponseEntity.ok(refresh(request));
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(start(request));
	}

	private WidgetSessionResponse refresh(WidgetSessionRequest request) {
		RefreshableContact refreshable;
		try {
			refreshable = this.sessionTokens.verifyForRefresh(request.sessionToken());
		}
		catch (com.byteflock.chatplatform.tenancy.InvalidSessionException ex) {
			// An unusable stored token must not silently start a new contact's session.
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		String tenantId = refreshable.caller().tenantId();
		Inbox inbox = this.directory.inbox(tenantId, refreshable.caller().inboxId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Contact contact = this.directory.contact(tenantId, UUID.fromString(refreshable.caller().principalId()))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		IssuedSession session = this.sessionTokens.issueContact(tenantId, contact.id(), inbox.id(),
				refreshable.authTime());
		return response(session, inbox, contact);
	}

	private WidgetSessionResponse start(WidgetSessionRequest request) {
		Inbox inbox = this.directory.inboxByWidgetKey(request.widgetKey())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Contact contact = (request.externalId() != null && !request.externalId().isBlank())
				? this.directory.ensureContact(inbox.tenantId(), request.externalId(), request.displayName())
				: this.directory.createAnonymousContact(inbox.tenantId());
		IssuedSession session = this.sessionTokens.issueContact(inbox.tenantId(), contact.id(), inbox.id());
		return response(session, inbox, contact);
	}

	private WidgetSessionResponse response(IssuedSession session, Inbox inbox, Contact contact) {
		return new WidgetSessionResponse(session.token(), session.expiresAt(), inbox.tenantId(),
				new WidgetSessionResponse.InboxView(inbox.id(), inbox.name()),
				new WidgetSessionResponse.ContactView(contact.id(), contact.displayName()));
	}

}
