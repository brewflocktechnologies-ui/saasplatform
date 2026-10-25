-- V2: chat MVP (specs/mvpsetup.spec.md). IMMUTABLE once applied; add V3__... for changes.
-- Demo identities (userone/agentbob) are enforced by the API, not the schema, so real identities can replace them.

CREATE TABLE chat_conversations (
    id                 UUID         PRIMARY KEY DEFAULT uuidv7(),
    visitor_id         VARCHAR(80)  NOT NULL,
    assigned_agent_id  VARCHAR(80)  NOT NULL,
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    last_message_at    TIMESTAMPTZ
);

-- At most one OPEN conversation per visitor; get-or-create relies on this (INSERT ... ON CONFLICT DO NOTHING).
CREATE UNIQUE INDEX ux_chat_conversations_open_visitor
    ON chat_conversations (visitor_id) WHERE status = 'OPEN';

-- Agent inbox ordering by most recent activity.
CREATE INDEX ix_chat_conversations_agent_activity
    ON chat_conversations (assigned_agent_id, (COALESCE(last_message_at, created_at)) DESC);

CREATE TABLE chat_messages (
    id               UUID         PRIMARY KEY DEFAULT uuidv7(),
    conversation_id  UUID         NOT NULL REFERENCES chat_conversations (id),
    sender_id        VARCHAR(80)  NOT NULL,
    sender_role      VARCHAR(20)  NOT NULL CHECK (sender_role IN ('VISITOR', 'AGENT')),
    body             TEXT         NOT NULL CHECK (length(btrim(body)) > 0 AND char_length(body) <= 4000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

-- History query: WHERE conversation_id = ? ORDER BY created_at, id
CREATE INDEX ix_chat_messages_conversation_created
    ON chat_messages (conversation_id, created_at, id);
