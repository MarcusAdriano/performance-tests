package dev.perf.chat.conversation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({ "sync", "api" })
public class ConversationController {

	private final ConversationService service;

	public ConversationController(ConversationService service) {
		this.service = service;
	}

	@PostMapping("/conversations")
	public ResponseEntity<CreateConversationResponse> createConversation() {
		UUID id = this.service.createConversation();
		return ResponseEntity.created(URI.create("/conversations/" + id)).body(new CreateConversationResponse(id));
	}

	@PostMapping("/conversations/{conversationId}/messages")
	public ResponseEntity<TurnResponse> sendMessage(@PathVariable UUID conversationId,
			@Valid @RequestBody SendMessageRequest request) {
		ChatTurn turn = this.service.sendMessage(conversationId, request.content());
		// sync: already DONE/FAILED -> 200. api: PENDING, the worker will process it -> 202
		// (poll GET /messages/{id}). Clients assert on the "status" field in both cases.
		HttpStatus status = turn.isFinished() ? HttpStatus.OK : HttpStatus.ACCEPTED;
		return ResponseEntity.status(status).body(TurnResponse.from(turn));
	}

	@GetMapping("/messages/{messageId}")
	public TurnResponse getMessage(@PathVariable UUID messageId) {
		return TurnResponse.from(this.service.getTurn(messageId));
	}

	@GetMapping("/conversations/{conversationId}/messages")
	public List<TurnResponse> history(@PathVariable UUID conversationId) {
		return this.service.history(conversationId).stream().map(TurnResponse::from).toList();
	}

}
