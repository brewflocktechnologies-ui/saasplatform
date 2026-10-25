# ADR-0003: Database migrations run as a dedicated Flyway job/image

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Running migrations on application startup couples schema changes to every replica's boot, needs DDL rights for the
runtime user, and makes rollouts with several replicas race on the schema.

## Decision

Migrations live in `database/flywayMigration/sql` and run from a separate image based on `flyway/flyway:13.11.1-alpine`
as a one-shot job (`flyway-migration` Compose service; later a Kubernetes Job / pipeline step). The job uses the
`platform_migrator` role (schema owner). The backend uses `platform_app` (DML only via default privileges) and has
no Flyway runtime dependency. Integration tests apply the same SQL with Flyway (test scope only).

## Alternatives

- Flyway/Liquibase inside Spring Boot startup: simplest, but violates least privilege and couples deploys.
- Manual SQL scripts: no versioning or validation.

## Consequences

- Deployment order matters: migrate → deploy app (enforced locally with `service_completed_successfully`).
- Schema changes must be backward compatible with the currently running app version (expand/contract).
- Applied migrations are immutable; fixes are new versions.
- Validated: `info`, `validate`, `migrate` succeed; the app role is denied `CREATE TABLE` and `DROP TABLE`;
  backend restarts leave `flyway_schema_history` unchanged.
