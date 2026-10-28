-- V3: multi-tenant conversation platform (specs/features/conversation-platform/feature.spec.md, Plan 02 T1).
-- IMMUTABLE once applied; add V4__... for changes. V1 and V2 stay untouched: the demo keeps working until Plan 06.
-- Tables, columns, keys, checks and indexes follow docs/architecture/domain-model.md section 3 exactly (Plan 01 ruling
-- R-P7): every column is NOT NULL unless it is listed as nullable there.
-- Runs as the migration (DDL owner) role in schema "platform"; the runtime app role gets DML through the default
-- privileges created by database/postgres-init/01-roles.sh and never runs migrations itself (ADR-0003).

CREATE TABLE tenants (
    id          TEXT        PRIMARY KEY,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

COMMENT ON TABLE tenants IS 'Tenant = security boundary; every tenant-owned row carries tenant_id (ADR-0008)';

CREATE TABLE inboxes (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           TEXT        NOT NULL REFERENCES tenants (id),
    name                TEXT        NOT NULL,
    widget_key          TEXT        NOT NULL UNIQUE,
    allocation_queue_id TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

COMMENT ON TABLE inboxes IS 'Inbox of a tenant; widget_key is public (it identifies, it does not authorize)';

CREATE TABLE contacts (
    id           UUID        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    TEXT        NOT NULL REFERENCES tenants (id),
    external_id  TEXT,
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- A known visitor identity is unique per tenant; anonymous contacts have no external_id.
CREATE UNIQUE INDEX ux_contacts_tenant_external_id
    ON contacts (tenant_id, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE agents (
    tenant_id    TEXT        NOT NULL REFERENCES tenants (id),
    id           TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, id)
);

COMMENT ON TABLE agents IS 'Agent roster; the same agent id may exist in two tenants and never sees the other''s data';

CREATE TABLE conversations (
    id                         UUID        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                  TEXT        NOT NULL,
    inbox_id                   UUID        NOT NULL REFERENCES inboxes (id),
    contact_id                 UUID        NOT NULL REFERENCES contacts (id),
    status                     TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PENDING', 'RESOLVED')),
    version                    BIGINT      NOT NULL DEFAULT 0,
    last_sequence              BIGINT      NOT NULL DEFAULT 0,
    last_message_at            TIMESTAMPTZ,
    last_message_preview       TEXT,
    assignment_state           TEXT        NOT NULL DEFAULT 'NONE' CHECK (assignment_state IN ('NONE', 'REQUESTED',
                                                                                           'QUEUED', 'ASSIGNED',
                                                                                           'RELEASE_PENDING',
                                                                                           'REOPEN_PENDING', 'FAILED')),
    assigned_agent_id          TEXT,
    assignment_decision_id     UUID,
    assignment_reason          TEXT,
    assignment_explanation     TEXT,
    assignment_attempts        INT         NOT NULL DEFAULT 0,
    assignment_setup_failures  INT         NOT NULL DEFAULT 0,
    assignment_next_attempt_at TIMESTAMPTZ,
    resolved_at                TIMESTAMPTZ,
    archived_at                TIMESTAMPTZ,
    archived_by                TEXT,
    archive_reason             TEXT,
    purged_at                  TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

COMMENT ON TABLE conversations IS 'Conversation with independent status, archive marker and assignment projection '
    '(docs/architecture/domain-model.md sections 2 to 6)';
COMMENT ON COLUMN conversations.version IS 'Optimistic lock and event version: +1 iff the same transaction appends a '
    'conversation.created/conversation.updated outbox row (domain-model.md section 5.4, R-P23); purge is the exception';
COMMENT ON COLUMN conversations.last_sequence IS 'Highest message sequence in this conversation, gap-free; assigned as '
    'last_sequence + 1 under the row lock while sending (domain-model.md section 7, D4/R2)';
COMMENT ON COLUMN conversations.assignment_state IS 'Projection of the allocation service assignment; the engine owns '
    'the record, this column only mirrors it (domain-model.md section 6, ADR-0009)';

-- At most one non-resolved, non-archived conversation per (tenant, inbox, contact): makes start/resume race-safe (D11).
CREATE UNIQUE INDEX ux_conversations_active_per_contact
    ON conversations (tenant_id, inbox_id, contact_id) WHERE status <> 'RESOLVED' AND archived_at IS NULL;

CREATE INDEX ix_conversations_inbox_activity
    ON conversations (tenant_id, inbox_id, archived_at, status, (COALESCE(last_message_at, created_at)) DESC, id DESC);

CREATE INDEX ix_conversations_agent_activity
    ON conversations (tenant_id, assigned_agent_id, archived_at, status, (COALESCE(last_message_at, created_at)) DESC,
                      id DESC);

-- Worker claim: FAILED is included because the worker re-reads FAILED rows every failed-poll (R-P17).
CREATE INDEX ix_conversations_assignment_due
    ON conversations (assignment_next_attempt_at)
    WHERE assignment_state IN ('REQUESTED', 'QUEUED', 'FAILED', 'RELEASE_PENDING', 'REOPEN_PENDING');

-- Retention scan: covers both purge eligibility branches (resolved before cutoff, or archived before cutoff, R-P25).
CREATE INDEX ix_conversations_purge_due
    ON conversations (tenant_id, id)
    WHERE purged_at IS NULL AND (status = 'RESOLVED' OR archived_at IS NOT NULL);

CREATE TABLE conversation_participants (
    conversation_id  UUID        NOT NULL REFERENCES conversations (id),
    tenant_id        TEXT        NOT NULL,
    participant_type TEXT        NOT NULL CHECK (participant_type IN ('CONTACT', 'AGENT', 'BOT', 'SYSTEM')),
    participant_id   TEXT        NOT NULL,
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (conversation_id, participant_type, participant_id)
);

CREATE TABLE messages (
    id                  UUID        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           TEXT        NOT NULL,
    conversation_id     UUID        NOT NULL REFERENCES conversations (id),
    sequence            BIGINT      NOT NULL,
    sender_type         TEXT        NOT NULL CHECK (sender_type IN ('CONTACT', 'AGENT', 'BOT', 'SYSTEM')),
    sender_id           TEXT        NOT NULL,
    message_type        TEXT        NOT NULL DEFAULT 'TEXT' CHECK (message_type IN ('TEXT')),
    body                TEXT        NOT NULL CHECK (length(btrim(body)) > 0 AND char_length(body) <= 4000),
    client_message_id   TEXT        CHECK (client_message_id IS NULL OR char_length(client_message_id) BETWEEN 1 AND 64),
    request_fingerprint TEXT,
    reply_to_message_id UUID        REFERENCES messages (id),
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    edited_at           TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ,
    deleted_by          TEXT,
    deletion_reason     TEXT
);

-- Gap-free per-conversation ordering and the history page index (D4, R2).
CREATE UNIQUE INDEX ux_messages_conversation_sequence ON messages (tenant_id, conversation_id, sequence);

-- Idempotent send: one row per (conversation, sender, clientMessageId) (D5).
CREATE UNIQUE INDEX ux_messages_client_message_id
    ON messages (conversation_id, sender_type, sender_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

CREATE TABLE message_attachments (
    id           UUID        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    TEXT        NOT NULL,
    message_id   UUID        NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    file_name    TEXT        NOT NULL,
    content_type TEXT        NOT NULL,
    size_bytes   BIGINT      NOT NULL,
    storage_key  TEXT        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('PENDING', 'READY', 'DELETED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

COMMENT ON TABLE message_attachments IS 'Schema only (NEXT): no upload endpoint; the retention purge deletes these rows';

CREATE TABLE conversation_read_cursors (
    conversation_id   UUID        NOT NULL REFERENCES conversations (id),
    tenant_id         TEXT        NOT NULL,
    participant_type  TEXT        NOT NULL,
    participant_id    TEXT        NOT NULL,
    last_read_sequence BIGINT     NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (conversation_id, participant_type, participant_id)
);

CREATE TABLE conversation_state_events (
    id              UUID        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       TEXT        NOT NULL,
    conversation_id UUID        NOT NULL REFERENCES conversations (id),
    event_type      TEXT        NOT NULL,
    actor_type      TEXT        NOT NULL,
    actor_id        TEXT        NOT NULL,
    from_status     TEXT        NOT NULL,
    to_status       TEXT        NOT NULL,
    details         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Append-only audit of lifecycle and assignment changes; survives the purge (details.reason is cleared, R-P31 M10).
CREATE INDEX ix_conversation_state_events_conversation
    ON conversation_state_events (tenant_id, conversation_id, created_at);

CREATE TABLE outbox_events (
    -- No default: OutboxAppender reads SELECT uuidv7() first so the stored envelope carries the same eventId.
    event_id        UUID        PRIMARY KEY,
    tenant_id       TEXT        NOT NULL,
    aggregate_type  TEXT        NOT NULL,
    aggregate_id    TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    schema_version  INT         NOT NULL DEFAULT 1,
    channels        TEXT[]      NOT NULL,
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'PARKED')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    locked_by       TEXT,
    locked_until    TIMESTAMPTZ,
    last_error      TEXT,
    trace_id        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at    TIMESTAMPTZ
);

COMMENT ON TABLE outbox_events IS 'Transactional outbox: one row per durable domain event, written in the change '
    'transaction and published to Centrifugo at least once (ADR-0007)';

CREATE INDEX ix_outbox_due ON outbox_events (next_attempt_at, created_at) WHERE status = 'PENDING';

CREATE INDEX ix_outbox_aggregate ON outbox_events (tenant_id, aggregate_type, aggregate_id);

CREATE TABLE retention_policies (
    tenant_id               TEXT        PRIMARY KEY REFERENCES tenants (id),
    resolved_retention_days INT         NOT NULL CHECK (resolved_retention_days > 0),
    legal_hold              BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE purge_runs (
    id                     UUID        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id              TEXT        NOT NULL,
    dry_run                BOOLEAN     NOT NULL,
    cutoff                 TIMESTAMPTZ NOT NULL,
    status                 TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    conversations_examined INT         NOT NULL DEFAULT 0,
    messages_deleted       INT         NOT NULL DEFAULT 0,
    attachments_deleted    INT         NOT NULL DEFAULT 0,
    cursors_deleted        INT         NOT NULL DEFAULT 0,
    batches                INT         NOT NULL DEFAULT 0,
    last_error             TEXT,
    started_at             TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at            TIMESTAMPTZ
);

COMMENT ON TABLE purge_runs IS 'One row per retention purge run, dry runs included (audit of the manual purge)';
