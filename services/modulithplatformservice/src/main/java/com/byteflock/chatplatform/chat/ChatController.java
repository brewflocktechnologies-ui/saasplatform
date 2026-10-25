package com.byteflock.chatplatform.chat;

import java.util.List;
import java.util.UUID;

import com.byteflock.chatplatform.chat.ChatDtos.Conversation;
import com.byteflock.chatplatform.chat.ChatDtos.Message;
import com.byteflock.chatplatform.chat.ChatDtos.SendMessageRequest;
import com.byteflock.chatplatform.chat.ChatDtos.SendMessageResponse;
import com.byteflock.chatplatform.chat.ChatService.EnsuredConversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** LOCAL POC — NOT REAL AUTHENTICATION. See {@link DemoActor}. */
@RestController
@RequestMapping("/api/v1/demo/conversations")
class ChatController {

	private final ChatService chat;

	ChatController(ChatService chat) {
		this.chat = chat;
	}

	/** 201 when the demo conversation was created by this call, 200 when the open one is reused. */
	@PostMapping
	ResponseEntity<Conversation> ensureConversation() {
		EnsuredConversation result = this.chat.ensureDemoConversation();
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(result.conversation());
	}

	@GetMapping
	List<Conversation> inbox(@RequestParam(required = false) String actorId) {
		return this.chat.inbox(actorId);
	}

	@GetMapping("/{conversationId}/messages")
	List<Message> history(@PathVariable UUID conversationId, @RequestParam(required = false) String actorId) {
		return this.chat.history(conversationId, actorId);
	}

	@PostMapping("/{conversationId}/messages")
	ResponseEntity<SendMessageResponse> send(@PathVariable UUID conversationId,
			@RequestBody SendMessageRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.chat.send(conversationId, request.actorId(), request.text()));
	}

}
