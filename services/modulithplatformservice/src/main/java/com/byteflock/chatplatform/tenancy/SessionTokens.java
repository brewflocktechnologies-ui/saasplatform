package com.byteflock.chatplatform.tenancy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies the platform's one session token for both principals: an HS256 JWT signed with
 * {@code APP_SESSION_TOKEN_HMAC_SECRET_KEY} (ADR-0008).
 * <p>
 * Claims: {@code sub} (contact id or agent id), {@code tenant}, {@code typ} ({@code contact} or {@code agent}),
 * {@code inbox} for contacts, {@code iat}, {@code exp} and, for contacts, {@code auth_time} — the time the session was
 * first issued. Expiry is checked against the injected {@link Clock}, not Nimbus' system-time verifier, so tests can
 * move time. {@link #verifyForRefresh} skips only the expiry check and rejects a contact token whose {@code auth_time}
 * is older than {@code app.sessions.contact-refresh-max-age}, so refreshing does not extend a session for ever
 * (Plan 01 ruling R-P31 M4).
 * <p>
 * LOCAL POC — NOT REAL AUTHENTICATION: the token is a development identity until OIDC replaces it.
 */
@Component
public class SessionTokens {

	static final String TYPE = "typ";

	static final String TENANT = "tenant";

	static final String INBOX = "inbox";

	static final String AUTH_TIME = "auth_time";

	static final String CONTACT = "contact";

	static final String AGENT = "agent";

	private final MACSigner signer;

	private final MACVerifier verifier;

	private final Duration contactTtl;

	private final Duration contactRefreshMaxAge;

	private final Duration agentTtl;

	private final Clock clock;

	public SessionTokens(@Value("${app.sessions.secret}") String secret,
			@Value("${app.sessions.contact-ttl}") Duration contactTtl,
			@Value("${app.sessions.contact-refresh-max-age}") Duration contactRefreshMaxAge,
			@Value("${app.sessions.agent-ttl}") Duration agentTtl, Clock clock) {
		this.signer = signer(secret);
		this.verifier = verifier(secret);
		this.contactTtl = contactTtl;
		this.contactRefreshMaxAge = contactRefreshMaxAge;
		this.agentTtl = agentTtl;
		this.clock = clock;
	}

	/** New contact session; {@code auth_time} starts now. */
	public IssuedSession issueContact(String tenantId, UUID contactId, UUID inboxId) {
		return issueContact(tenantId, contactId, inboxId, this.clock.instant());
	}

	/**
	 * Refreshed contact session: the original {@code auth_time} is copied unchanged, so the absolute refresh window is
	 * measured from the first issue (R-P31 M4).
	 */
	public IssuedSession issueContact(String tenantId, UUID contactId, UUID inboxId, Instant authTime) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(this.contactTtl);
		JWTClaimsSet.Builder claims = claims(CONTACT, tenantId, contactId.toString(), now, expiresAt)
			.claim(INBOX, inboxId.toString())
			.claim(AUTH_TIME, Date.from(authTime));
		return new IssuedSession(sign(claims.build()), expiresAt);
	}

	public IssuedSession issueAgent(String tenantId, String agentId) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(this.agentTtl);
		return new IssuedSession(sign(claims(AGENT, tenantId, agentId, now, expiresAt).build()), expiresAt);
	}

	/** Verifies signature, expiry and shape; returns the caller the token stands for. */
	public Caller verify(String token) {
		JWTClaimsSet claims = parse(token);
		Instant expiresAt = instant(claims.getExpirationTime());
		if (expiresAt == null || !expiresAt.isAfter(this.clock.instant())) {
			throw new InvalidSessionException("expired session");
		}
		return caller(claims);
	}

	/** Verifies a contact token for refresh: signature and {@code typ} only, plus the absolute refresh window. */
	public RefreshableContact verifyForRefresh(String token) {
		JWTClaimsSet claims = parse(token);
		if (!CONTACT.equals(string(claims.getClaim(TYPE)))) {
			throw new InvalidSessionException("not a contact session");
		}
		Instant authTime = instant(claims.getClaim(AUTH_TIME));
		if (authTime == null) {
			throw new InvalidSessionException("session has no auth_time");
		}
		if (authTime.plus(this.contactRefreshMaxAge).isBefore(this.clock.instant())) {
			throw new InvalidSessionException("session too old to refresh");
		}
		return new RefreshableContact(caller(claims), authTime);
	}

	private JWTClaimsSet.Builder claims(String type, String tenantId, String subject, Instant issuedAt, Instant expiresAt) {
		return new JWTClaimsSet.Builder().subject(subject)
			.claim(TYPE, type)
			.claim(TENANT, tenantId)
			.issueTime(Date.from(issuedAt))
			.expirationTime(Date.from(expiresAt));
	}

	private String sign(JWTClaimsSet claims) {
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		try {
			jwt.sign(this.signer);
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Could not sign a session token", ex);
		}
		return jwt.serialize();
	}

	private JWTClaimsSet parse(String token) {
		if (token == null || token.isBlank()) {
			throw new InvalidSessionException("missing session token");
		}
		SignedJWT jwt;
		try {
			jwt = SignedJWT.parse(token.trim());
		}
		catch (java.text.ParseException ex) {
			throw new InvalidSessionException("unreadable session token");
		}
		try {
			if (!jwt.verify(this.verifier)) {
				throw new InvalidSessionException("bad session signature");
			}
		}
		catch (JOSEException ex) {
			throw new InvalidSessionException("bad session signature");
		}
		try {
			return jwt.getJWTClaimsSet();
		}
		catch (java.text.ParseException ex) {
			throw new InvalidSessionException("unreadable session token");
		}
	}

	private Caller caller(JWTClaimsSet claims) {
		String tenantId = string(claims.getClaim(TENANT));
		String subject = claims.getSubject();
		String type = string(claims.getClaim(TYPE));
		if (tenantId == null || subject == null || type == null) {
			throw new InvalidSessionException("incomplete session token");
		}
		return switch (type) {
			case CONTACT -> {
				UUID inboxId = uuid(claims.getClaim(INBOX));
				if (inboxId == null) {
					throw new InvalidSessionException("contact session without inbox");
				}
				yield new Caller(tenantId, PrincipalType.CONTACT, subject, inboxId);
			}
			case AGENT -> new Caller(tenantId, PrincipalType.AGENT, subject, null);
			default -> throw new InvalidSessionException("unknown session type");
		};
	}

	private static String string(Object value) {
		return (value instanceof String text) ? text : null;
	}

	private static UUID uuid(Object value) {
		if (!(value instanceof String text)) {
			return null;
		}
		try {
			return UUID.fromString(text);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidSessionException("bad inbox claim");
		}
	}

	private static Instant instant(Object value) {
		// Registered claims come back as Date; custom date claims are serialised as epoch seconds.
		if (value instanceof Date date) {
			return date.toInstant();
		}
		if (value instanceof Number seconds) {
			return Instant.ofEpochSecond(seconds.longValue());
		}
		return null;
	}

	private static String secretOrFail(String secret) {
		if (secret == null || secret.length() < 32) {
			// Fail fast at startup; never include the secret in the message.
			throw new IllegalStateException(
					"APP_SESSION_TOKEN_HMAC_SECRET_KEY must be set to at least 32 characters (run scripts/dev-setup.ps1)");
		}
		return secret;
	}

	private static MACSigner signer(String secret) {
		try {
			return new MACSigner(secretOrFail(secret));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("APP_SESSION_TOKEN_HMAC_SECRET_KEY is not a usable HS256 key", ex);
		}
	}

	private static MACVerifier verifier(String secret) {
		try {
			return new MACVerifier(secretOrFail(secret));
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("APP_SESSION_TOKEN_HMAC_SECRET_KEY is not a usable HS256 key", ex);
		}
	}

}
