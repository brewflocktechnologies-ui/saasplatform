package com.byteflock.chatplatform.conversation.internal;

import com.byteflock.chatplatform.conversation.ConversationAccessDeniedException;
import com.byteflock.chatplatform.realtime.IssuedToken;
import com.byteflock.chatplatform.realtime.RealtimeTokenIssuer;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.PrincipalType;
import com.byteflock.chatplatform.tenancy.RequiresPrincipal;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Centrifugo connection and subscription tokens for the conversation channels (D12, D14). The connection subject is
 * {@code <tenantId>:<contact|agent>:<principalId>}; a subscription token is bound to one exact channel that
 * {@link ChannelAuthorizer} has approved. Browsers never receive an API key or an HMAC secret.
 * <p>
 * Named {@code ConversationRealtimeTokenController} on purpose: the demo module already has a
 * {@code RealtimeTokenController} and the default bean names would clash until Plan 06 removes the demo.
 */
@RestController
class ConversationRealtimeTokenController {

	private final RealtimeTokenIssuer tokens;

	private final ChannelAuthorizer channelAuthorizer;

	private final MeterRegistry meters;

	ConversationRealtimeTokenController(RealtimeTokenIssuer tokens, ChannelAuthorizer channelAuthorizer,
			MeterRegistry meters) {
		this.tokens = tokens;
		this.channelAuthorizer = channelAuthorizer;
		this.meters = meters;
	}

	@GetMapping("/api/v1/widget/realtime/connection-token")
	@RequiresPrincipal(PrincipalType.CONTACT)
	IssuedToken widgetConnectionToken(Caller caller) {
		return issue("connection", () -> this.tokens.connectionToken(subject(caller)));
	}

	@GetMapping("/api/v1/widget/realtime/subscription-token")
	@RequiresPrincipal(PrincipalType.CONTACT)
	IssuedToken widgetSubscriptionToken(Caller caller, @RequestParam("channel") String channel) {
		return issue("subscription", () -> {
			this.channelAuthorizer.authorize(caller, channel);
			return this.tokens.subscriptionToken(subject(caller), channel);
		});
	}

	@GetMapping("/api/v1/agent/realtime/connection-token")
	@RequiresPrincipal(PrincipalType.AGENT)
	IssuedToken agentConnectionToken(Caller caller) {
		return issue("connection", () -> this.tokens.connectionToken(subject(caller)));
	}

	@GetMapping("/api/v1/agent/realtime/subscription-token")
	@RequiresPrincipal(PrincipalType.AGENT)
	IssuedToken agentSubscriptionToken(Caller caller, @RequestParam("channel") String channel) {
		return issue("subscription", () -> {
			this.channelAuthorizer.authorize(caller, channel);
			return this.tokens.subscriptionToken(subject(caller), channel);
		});
	}

	private IssuedToken issue(String type, java.util.function.Supplier<IssuedToken> action) {
		try {
			IssuedToken token = action.get();
			this.meters.counter("realtime.tokens", "type", type, "outcome", "issued").increment();
			return token;
		}
		catch (ConversationAccessDeniedException | com.byteflock.chatplatform.conversation.ConversationNotFoundException
				| IllegalArgumentException ex) {
			this.meters.counter("realtime.tokens", "type", type, "outcome", "denied").increment();
			throw ex;
		}
	}

	private static String subject(Caller caller) {
		String kind = (caller.type() == PrincipalType.CONTACT) ? "contact" : "agent";
		return caller.tenantId() + ":" + kind + ":" + caller.principalId();
	}

}
