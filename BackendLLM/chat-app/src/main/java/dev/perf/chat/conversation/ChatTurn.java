package dev.perf.chat.conversation;

import java.time.Instant;
import java.util.UUID;

public record ChatTurn(UUID id, UUID conversationId, String userContent, String assistantContent, TurnStatus status,
		String error, Instant createdAt, Instant startedAt, Instant completedAt) {

	public static ChatTurn pending(UUID conversationId, String userContent, Instant now) {
		return new ChatTurn(UUID.randomUUID(), conversationId, userContent, null, TurnStatus.PENDING, null, now, null,
				null);
	}

	public boolean isFinished() {
		return this.status == TurnStatus.DONE || this.status == TurnStatus.FAILED;
	}

}
