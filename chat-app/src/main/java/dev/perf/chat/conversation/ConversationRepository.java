package dev.perf.chat.conversation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

	private final JdbcClient jdbc;

	public ConversationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(UUID id, Instant createdAt) {
		this.jdbc.sql("INSERT INTO conversations (id, created_at) VALUES (:id, :createdAt)")
			.param("id", id)
			.param("createdAt", DbTime.toDb(createdAt))
			.update();
	}

	public boolean exists(UUID id) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM conversations WHERE id = :id)")
			.param("id", id)
			.query(Boolean.class)
			.single();
	}

}
