package com.byteflock.chatplatform.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RealtimeTokenIssuerTests {

	static final String CONNECTION_SECRET = "connection-secret-0123456789abcdef-xyz";

	static final String SUBSCRIPTION_SECRET = "subscription-secret-0123456789abcdef-xyz";

	final Instant now = Instant.parse("2026-10-25T12:00:00Z");

	final RealtimeTokenIssuer issuer = new RealtimeTokenIssuer(CONNECTION_SECRET, SUBSCRIPTION_SECRET,
			Duration.ofMinutes(5), Clock.fixed(this.now, ZoneOffset.UTC));

	@Test
	void connectionTokenIsSignedWithConnectionSecretAndExpires() throws Exception {
		IssuedToken token = this.issuer.connectionToken("userone");
		SignedJWT jwt = SignedJWT.parse(token.token());

		assertThat(jwt.verify(new MACVerifier(CONNECTION_SECRET))).isTrue();
		assertThat(jwt.verify(new MACVerifier(SUBSCRIPTION_SECRET))).isFalse();
		assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("userone");
		assertThat(jwt.getJWTClaimsSet().getClaim("channel")).isNull();
		assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(this.now.plusSeconds(300));
		assertThat(token.expiresAt()).isEqualTo(this.now.plusSeconds(300));
	}

	@Test
	void subscriptionTokenIsBoundToActorAndChannel() throws Exception {
		SignedJWT jwt = SignedJWT.parse(this.issuer.subscriptionToken("agentbob", "chat:inbox").token());

		assertThat(jwt.verify(new MACVerifier(SUBSCRIPTION_SECRET))).isTrue();
		assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("agentbob");
		assertThat(jwt.getJWTClaimsSet().getStringClaim("channel")).isEqualTo("chat:inbox");
		assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isNotNull();
	}

	@Test
	void rejectsMissingOrShortSecretsAtStartup() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new RealtimeTokenIssuer("", SUBSCRIPTION_SECRET, Duration.ofMinutes(5), Clock.systemUTC()))
			.withMessageContaining("CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY")
			.withMessageNotContaining(SUBSCRIPTION_SECRET);
	}

}
