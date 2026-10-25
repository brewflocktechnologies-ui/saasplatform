-- V1: setup proof table. IMMUTABLE once applied in any shared environment; add V2__... for changes.
-- Runs as the migration (DDL owner) role in schema "platform"; the runtime app role only gets DML via default privileges.
CREATE TABLE platform_bootstrap (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setup_key   TEXT        NOT NULL UNIQUE,
    message     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE platform_bootstrap IS 'Local setup proof read by GET /api/v1/bootstrap';

-- Documented local setup record (not a secret, not product data).
INSERT INTO platform_bootstrap (setup_key, message)
VALUES ('local-poc', 'Flyway migration V1 applied; backend can read PostgreSQL.');
