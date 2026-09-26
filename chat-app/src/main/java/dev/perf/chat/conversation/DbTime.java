package dev.perf.chat.conversation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** timestamptz <-> Instant conversion, always in UTC. */
final class DbTime {

	private DbTime() {
	}

	static OffsetDateTime toDb(Instant instant) {
		return (instant != null) ? instant.atOffset(ZoneOffset.UTC) : null;
	}

	static Instant fromDb(OffsetDateTime value) {
		return (value != null) ? value.toInstant() : null;
	}

}
