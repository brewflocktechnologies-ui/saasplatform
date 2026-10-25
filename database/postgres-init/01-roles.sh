#!/bin/sh
# Runs ONCE when the postgres-data volume is first initialised (docker-entrypoint-initdb.d).
# Creates least-privilege roles:
#   migrator role - owns schema "platform", used only by the Flyway runner (DDL)
#   app role      - runtime Spring Boot user, DML only, cannot create/alter objects
# Changing passwords later requires ALTER ROLE (or the dev-only reset in docs/runbooks/local-stack.md).
set -eu

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v migrator_user="$POSTGRES_MIGRATOR_USER" -v migrator_password="$POSTGRES_MIGRATOR_PASSWORD" \
  -v app_user="$POSTGRES_APP_USER" -v app_password="$POSTGRES_APP_PASSWORD" \
  -v db="$POSTGRES_DB" <<'SQL'
CREATE ROLE :"migrator_user" LOGIN PASSWORD :'migrator_password';
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"migrator_user", :"app_user";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA platform AUTHORIZATION :"migrator_user";
GRANT USAGE ON SCHEMA platform TO :"app_user";

-- Tables/sequences created later by the migrator are automatically readable/writable (not alterable) by the app.
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA platform
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_user" IN SCHEMA platform
  GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";

ALTER ROLE :"app_user" SET search_path = platform;
ALTER ROLE :"migrator_user" SET search_path = platform;
SQL
