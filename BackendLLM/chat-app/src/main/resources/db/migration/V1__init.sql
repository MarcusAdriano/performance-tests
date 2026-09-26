CREATE TABLE conversations (
    id         uuid PRIMARY KEY,
    created_at timestamptz NOT NULL
);

-- One row per turn (user question + assistant answer).
-- started_at - created_at = time waiting (queue); completed_at - started_at = processing time.
CREATE TABLE chat_turns (
    id                uuid PRIMARY KEY,
    conversation_id   uuid        NOT NULL REFERENCES conversations (id),
    user_content      text        NOT NULL,
    assistant_content text,
    status            varchar(16) NOT NULL,
    error             text,
    created_at        timestamptz NOT NULL,
    started_at        timestamptz,
    completed_at      timestamptz
);

CREATE INDEX idx_chat_turns_conversation ON chat_turns (conversation_id, created_at);
