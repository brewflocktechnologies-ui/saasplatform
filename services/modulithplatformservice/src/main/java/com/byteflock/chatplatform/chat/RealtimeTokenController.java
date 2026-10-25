package com.byteflock.chatplatform.chat;

import java.util.UUID;

import com.byteflock.chatplatform.realtime.IssuedToken;
import com.byteflock.chatplatform.realtime.RealtimeTokenIssuer;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Short-lived Centrifugo tokens for the two demo actors. LOCAL POC — NOT REAL AUTHENTICATION: the point is to keep
 * channel permissions explicit (exact actor + exact channel) in the shape a real integration will use.
 */
@RestController
@RequestMapping("/api/v1/demo/realtime")
class RealtimeTokenController {

	private final RealtimeTokenIssuer tokens;

	private final ChatService chat;

	RealtimeTokenController(RealtimeTokenIssuer tokens, ChatService chat) {
		this.tokens = tokens;
		this.chat = chat;
	}

	@GetMapping("/connection-token")
	IssuedToken connectionToken(@RequestParam(required = false) String actorId) {
		return this.tokens.connectionToken(DemoActor.resolve(actorId).id);
	}

	/**
	 * agentbob: {@code chat:inbox} and conversations assigned to them; userone: only their own conversation channel.
	 */
	@GetMapping("/subscription-token")
	IssuedToken subscriptionToken(@RequestParam(required = false) String actorId,
			@RequestParam(required = false) String channel) {
		DemoActor actor = DemoActor.resolve(actorId);
		if (ChatChannels.INBOX.equals(channel)) {
			if (!actor.isAgent()) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the demo agent may subscribe to the inbox");
			}
		}
		else {
			UUID conversationId = ChatChannels.conversationId(channel)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"channel must be chat:inbox or chat:conversation:<uuid>"));
			this.chat.authorize(actor, conversationId);
		}
		return this.tokens.subscriptionToken(actor.id, channel);
	}

}
