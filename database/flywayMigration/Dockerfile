# Dedicated one-shot migration runner (ADR-0003). Connection settings come from FLYWAY_* env vars at run time;
# no credentials are baked into the image.
FROM flyway/flyway:13.11.1-alpine

COPY sql/ /flyway/sql/

ENV FLYWAY_LOCATIONS=filesystem:/flyway/sql \
    FLYWAY_SCHEMAS=platform \
    FLYWAY_CONNECT_RETRIES=10 \
    FLYWAY_VALIDATE_MIGRATION_NAMING=true \
    REDGATE_DISABLE_TELEMETRY=true

# Image entrypoint is `flyway`; default command validates then applies pending migrations.
CMD ["migrate"]
