package com.byteflock.chatplatform.tenancy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Session token claims, expiry, refresh window and tampering (ADR-0008, Plan 01 ruling R-P31 M4). */
class SessionTokensTest {

	static final String SECRET = "test-session-secret-0123456789abcdef";

	static final String TENANT = "tenant-a";

	static final UUID CONTACT = UUID.fromString("01920000-0000-7000-8000-000000000001");

	static final UUID INBOX = UUID.fromString("01920000-0000-7000-8000-000000000002");

	final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T10:00:00Z"));

	final SessionTokens tokens = new SessionTokens(SECRET, Duration.ofHours(24), Duration.ofDays(30),
			Duration.ofHours(12), this.clock);

	@Test
	void issuesContactTokenWithClaims() {
		IssuedSession session = this.tokens.issueContact(TENANT, CONTACT, INBOX);

		Caller caller = this.tokens.verify(session.token());

		assertThat(caller).isEqualTo(new Caller(TENANT, PrincipalType.CONTACT, CONTACT.toString(), INBOX));
		assertThat(session.expiresAt()).isEqualTo(this.clock.instant().plus(Duration.ofHours(24)));
	}

	@Test
	void issuesAgentTokenWithoutInbox() {
		IssuedSession session = this.tokens.issueAgent(TENANT, "agentbob");

		Caller caller = this.tokens.verify(session.token());

		assertThat(caller).isEqualTo(new Caller(TENANT, PrincipalType.AGENT, "agentbob", null));
	}

	@Test
	void rejectsExpiredToken() {
		IssuedSession session = this.tokens.issueAgent(TENANT, "agentbob");

		this.clock.advance(Duration.ofHours(13));

		assertThatExceptionOfType(InvalidSessionException.class).isThrownBy(() -> this.tokens.verify(session.token()));
	}

	@Test
	void rejectsTamperedSignature() {
		String token = this.tokens.issueAgent(TENANT, "agentbob").token();
		int index = token.length() - 6;
		char replacement = (token.charAt(index) == 'A') ? 'B' : 'A';
		String tampered = token.substring(0, index) + replacement + token.substring(index + 1);

		assertThatExceptionOfType(InvalidSessionException.class).isThrownBy(() -> this.tokens.verify(tampered));
	}

	@Test
	void rejectsTokenSignedWithRealtimeSecret() throws Exception {
		// A Centrifugo connection token must never be accepted as a session.
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
			.subject("agentbob")
			.claim("tenant", TENANT)
			.claim("typ", "agent")
			.issueTime(Date.from(this.clock.instant()))
			.expirationTime(Date.from(this.clock.instant().plus(Duration.ofHours(1))))
			.build());
		jwt.sign(new MACSigner("test-connection-secret-0123456789abcdef"));

		assertThatExceptionOfType(InvalidSessionException.class).isThrownBy(() -> this.tokens.verify(jwt.serialize()));
	}

	@Test
	void rejectsUnknownTyp() throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
			.subject("admin")
			.claim("tenant", TENANT)
			.claim("typ", "admin")
			.issueTime(Date.from(this.clock.instant()))
			.expirationTime(Date.from(this.clock.instant().plus(Duration.ofHours(1))))
			.build());
		jwt.sign(new MACSigner(SECRET));

		assertThatExceptionOfType(InvalidSessionException.class).isThrownBy(() -> this.tokens.verify(jwt.serialize()));
	}

	@Test
	void rejectsGarbageToken() {
		assertThatExceptionOfType(InvalidSessionException.class).isThrownBy(() -> this.tokens.verify("not-a-jwt"));
		assertThatExceptionOfType(InvalidSessionException.class).isThrownBy(() -> this.tokens.verify(""));
	}

	@Test
	void verifyForRefreshAcceptsExpiredContactTokenOnly() {
		IssuedSession contact = this.tokens.issueContact(TENANT, CONTACT, INBOX);
		IssuedSession agent = this.tokens.issueAgent(TENANT, "agentbob");

		this.clock.advance(Duration.ofDays(2));

		RefreshableContact refreshable = this.tokens.verifyForRefresh(contact.token());
		assertThat(refreshable.caller()).isEqualTo(new Caller(TENANT, PrincipalType.CONTACT, CONTACT.toString(), INBOX));
		assertThat(refreshable.authTime()).isEqualTo(Instant.parse("2026-10-27T10:00:00Z"));

		assertThatExceptionOfType(InvalidSessionException.class)
			.isThrownBy(() -> this.tokens.verifyForRefresh(agent.token()));
		assertThatExceptionOfType(InvalidSessionException.class)
			.isThrownBy(() -> this.tokens.verifyForRefresh(contact.token() + "x"));
	}

	@Test
	void refreshWindowIsMeasuredFromFirstIssue() {
		IssuedSession contact = this.tokens.issueContact(TENANT, CONTACT, INBOX);

		// Day 29: still refreshable, and the refresh copies the original auth_time.
		this.clock.advance(Duration.ofDays(29));
		RefreshableContact refreshable = this.tokens.verifyForRefresh(contact.token());
		IssuedSession refreshed = this.tokens.issueContact(TENANT, CONTACT, INBOX, refreshable.authTime());

		// Day 31 from the first issue: the refreshed token is expired and too old to refresh again.
		this.clock.advance(Duration.ofDays(2));
		assertThatExceptionOfType(InvalidSessionException.class)
			.isThrownBy(() -> this.tokens.verifyForRefresh(refreshed.token()));
	}

	@Test
	void refusesASecretShorterThan32Characters() {
		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> new SessionTokens("short", Duration.ofHours(24), Duration.ofDays(30),
					Duration.ofHours(12), this.clock))
			.withMessageContaining("APP_SESSION_TOKEN_HMAC_SECRET_KEY");
	}

	/** Controllable clock; the production code checks {@code exp} against the injected clock, not system time. */
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

}
