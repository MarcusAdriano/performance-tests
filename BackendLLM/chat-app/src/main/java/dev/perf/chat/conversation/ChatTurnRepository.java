package dev.perf.chat.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL with auto-commit: every method is its own short transaction. Callers must
 * NOT wrap these calls in a transaction that spans the LLM calls (see
 * ConversationProcessor).
 */
@Repository
public class ChatTurnRepository {

	private static final String COLUMNS = "id, conversation_id, user_content, assistant_content, status, error, created_at, started_at, completed_at";

	private final JdbcClient jdbc;

	public ChatTurnRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(ChatTurn turn) {
		this.jdbc.sql("""
				INSERT INTO chat_turns (id, conversation_id, user_content, status, created_at)
				VALUES (:id, :conversationId, :userContent, :status, :createdAt)
				""")
			.param("id", turn.id())
			.param("conversationId", turn.conversationId())
			.param("userContent", turn.userContent())
			.param("status", turn.status().name())
			.param("createdAt", DbTime.toDb(turn.createdAt()))
			.update();
	}

	/**
	 * PENDING -> PROCESSING. Returns false when the turn was already picked up (e.g. a
	 * RabbitMQ redelivery), which makes processing idempotent.
	 */
	public boolean markProcessing(UUID id, Instant startedAt) {
		return this.jdbc
			.sql("UPDATE chat_turns SET status = 'PROCESSING', started_at = :startedAt WHERE id = :id AND status = 'PENDING'")
			.param("id", id)
			.param("startedAt", DbTime.toDb(startedAt))
			.update() == 1;
	}

	public void markDone(UUID id, String assistantContent, Instant completedAt) {
		this.jdbc
			.sql("UPDATE chat_turns SET status = 'DONE', assistant_content = :content, completed_at = :completedAt WHERE id = :id")
			.param("id", id)
			.param("content", assistantContent)
			.param("completedAt", DbTime.toDb(completedAt))
			.update();
	}

	public void markFailed(UUID id, String error, Instant completedAt) {
		this.jdbc.sql("UPDATE chat_turns SET status = 'FAILED', error = :error, completed_at = :completedAt WHERE id = :id")
			.param("id", id)
			.param("error", error)
			.param("completedAt", DbTime.toDb(completedAt))
			.update();
	}

	public Optional<ChatTurn> findById(UUID id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM chat_turns WHERE id = :id")
			.param("id", id)
			.query(ChatTurnRepository::map)
			.optional();
	}

	public List<ChatTurn> findByConversation(UUID conversationId) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM chat_turns WHERE conversation_id = :conversationId ORDER BY created_at, id")
			.param("conversationId", conversationId)
			.query(ChatTurnRepository::map)
			.list();
	}

	private static ChatTurn map(ResultSet rs, int rowNum) throws SQLException {
		return new ChatTurn(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
				rs.getString("user_content"), rs.getString("assistant_content"),
				TurnStatus.valueOf(rs.getString("status")), rs.getString("error"),
				DbTime.fromDb(rs.getObject("created_at", OffsetDateTime.class)),
				DbTime.fromDb(rs.getObject("started_at", OffsetDateTime.class)),
				DbTime.fromDb(rs.getObject("completed_at", OffsetDateTime.class)));
	}

}
