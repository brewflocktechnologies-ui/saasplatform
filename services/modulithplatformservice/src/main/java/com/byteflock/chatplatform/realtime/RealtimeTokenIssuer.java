package com.byteflock.chatplatform.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues HS256 JWTs for Centrifugo: connection tokens ({@code sub}) and subscription tokens ({@code sub} +
 * {@code channel}), signed with separate secrets (client.token / client.subscription_token in Centrifugo).
 * Callers must authorize the actor and channel before asking for a token.
 */
@Component
public class RealtimeTokenIssuer {

	private final MACSigner connectionSigner;

	private final MACSigner subscriptionSigner;

	private final Duration ttl;

	private final Clock clock;

	RealtimeTokenIssuer(@Value("${app.centrifugo.connection-token-secret}") String connectionSecret,
			@Value("${app.centrifugo.subscription-token-secret}") String subscriptionSecret,
			@Value("${app.centrifugo.token-ttl}") Duration ttl, Clock clock) {
		this.connectionSigner = signer("CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY", connectionSecret);
		this.subscriptionSigner = signer("CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY", subscriptionSecret);
		this.ttl = ttl;
		this.clock = clock;
	}

	public IssuedToken connectionToken(String subject) {
		return sign(this.connectionSigner, subject, null);
	}

	public IssuedToken subscriptionToken(String subject, String channel) {
		return sign(this.subscriptionSigner, subject, channel);
	}

	private IssuedToken sign(MACSigner signer, String subject, String channel) {
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(this.ttl);
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(subject)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(expiresAt));
		if (channel != null) {
			claims.claim("channel", channel);
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
		try {
			jwt.sign(signer);
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("Could not sign Centrifugo token", ex);
		}
		return new IssuedToken(jwt.serialize(), expiresAt);
	}

	private static MACSigner signer(String name, String secret) {
		try {
			return new MACSigner(secret);
		}
		catch (JOSEException ex) {
			// Fail fast at startup; never include the secret in the message.
			throw new IllegalStateException(name + " must be set to at least 32 characters (run scripts/dev-setup.ps1)",
					ex);
		}
	}

}
