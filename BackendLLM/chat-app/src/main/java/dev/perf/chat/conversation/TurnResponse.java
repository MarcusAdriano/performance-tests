package dev.perf.chat.conversation;

import java.time.Instant;
import java.util.UUID;

public record TurnResponse(UUID messageId, UUID conversationId, TurnStatus status, String content, String response,
		String error, Instant createdAt, Instant startedAt, Instant completedAt) {

	public static TurnResponse from(ChatTurn turn) {
		return new TurnResponse(turn.id(), turn.conversationId(), turn.status(), turn.userContent(),
				turn.assistantContent(), turn.error(), turn.createdAt(), turn.startedAt(), turn.completedAt());
	}

}
