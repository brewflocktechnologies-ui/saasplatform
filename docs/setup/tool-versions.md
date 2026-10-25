# Tool and image versions

Verified on **2026-10-25** on Windows 11 (Docker Desktop 29.9.2, Compose v5.6.1). Versions were picked from the
registries' newest stable releases on that date (Docker Hub tag listings, Maven Central metadata, npm registry,
start.spring.io metadata). No prereleases.

## Toolchain

| Tool | Version | Notes / reference |
|---|---|---|
| Node.js | 24.x LTS (host: 24.18.0; images: `node:24.22.1-alpine3.24`) | Next.js 16 needs Node ≥ 20.9; `engines.node >=24`. https://nextjs.org/docs/app/getting-started/installation |
| pnpm | 10.11.0 (pinned via `packageManager`, use Corepack) | Lockfile `pnpm-lock.yaml` committed |
| Nx | 24.0.1 (`nx`, `@nx/*`) | Created with `create-nx-workspace@24.0.1`. https://nx.dev/docs/quickstart |
| TypeScript | 6.0.3 | from the Nx preset |
| Next.js | 16.4.3 (React 19) | App Router, Turbopack build |
| Lit | 3.3.3 | https://lit.dev/docs/getting-started/ |
| Vite / Vitest | 8.3.5 / 5.0.x | Vite 8 uses Rolldown |
| Playwright | 1.64.1 (Chromium) | https://playwright.dev/docs/intro |
| Redocly CLI | 2.71.0 | OpenAPI 3.1 + AsyncAPI 3.0 lint |
| `centrifuge` (JS SDK) | 5.8.0 (verified 2026-10-25) | Official Centrifugal SDK; v5 SDK supports Centrifugo v6 server. https://www.npmjs.com/package/centrifuge |
| Nimbus JOSE + JWT | 10.10 (verified 2026-10-25) | HS256 connection/subscription tokens; not managed by Spring Boot, pinned in `pom.xml` |
| `pg` (tests only) | 8.23.1 | read-only DB assertion in Playwright E2E |
| JDK | 25 (host: 25.0.3 for validation; images: Temurin `25.0.4.1_1`) | Host default is JDK 27 — see note below |
| Maven | 3.10.0 via Maven Wrapper 3.3.4 | No global Maven needed |
| Spring Boot | 4.1.2 | Java 17–27 supported; https://docs.spring.io/spring-boot/system-requirements.html |
| Spring Modulith | 2.1.2 | start.spring.io lists Modulith for Boot `[4.0.0,4.3.0-M1)` → compatible with 4.1.2 |
| OpenTelemetry SDK | 1.62.0 (managed by Boot) + `opentelemetry-logback-appender-1.0` 2.28.0-alpha (matching instrumentation release) | |
| Flyway (tests only) | 12.4.0 (managed by Boot) | The migration **runner** image is 13.11.1 |

**JDK note:** the workstation's default `JAVA_HOME` is JDK 27. The project targets `--release 25` (enforced by
`maven-enforcer-plugin`, `[25,)`), so it also builds on 27, but all validation in `validation-report.md` was run with
`JAVA_HOME=C:\Program Files\Java\jdk-25.0.3`, and container images use Temurin 25.

## Container images (pinned)

| Service | Image | Reference |
|---|---|---|
| PostgreSQL | `postgres:18.6-alpine3.24` | https://hub.docker.com/_/postgres (PG 18 volume path `/var/lib/postgresql`) |
| Flyway runner | `flyway/flyway:13.11.1-alpine` | https://documentation.red-gate.com/flyway |
| Centrifugo | `centrifugo/centrifugo:v6.10.2` | https://centrifugal.dev/docs/getting-started/installation |
| OTel Collector | `otel/opentelemetry-collector-contrib:0.163.2` | exporters `otlp_grpc` / `otlp_http` (checked with `docker run --rm <image> components`) |
| Prometheus | `prom/prometheus:v3.15.1` | https://prometheus.io/docs/prometheus/latest/getting_started/ |
| Grafana | `grafana/grafana:13.2.4` | https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/ |
| Tempo | `grafana/tempo:3.1.1` | config from `example/docker-compose/single-binary` at tag v3.1.1 |
| Loki | `grafana/loki:3.7.9` | config from `cmd/loki/loki-local-config.yaml` at v3.7.9; OTLP at `/otlp` |
| Backend build/runtime | `eclipse-temurin:25.0.4.1_1-jdk-alpine-3.24` / `-jre-alpine-3.24` | |
| Dashboard build/runtime | `node:24.22.1-alpine3.24` | Next standalone server, runs as `node` user |
| Widget runtime | `nginxinc/nginx-unprivileged:1.31.7-alpine3.24` | serves static bundle on 8080 as non-root |
| CI lint | `rhysd/actionlint:1.7.12` | used to validate `.github/workflows/ci.yml` |

GitHub Actions used: `actions/checkout@v7`, `actions/setup-node@v7`, `actions/setup-java@v6`, `pnpm/action-setup@v6`,
`actions/upload-artifact@v7` (latest releases on the verification date).

## Compatibility findings

- `@nx/maven` was **not** used: Maven stays the source of truth and Nx calls the wrapper through `nx:run-commands`
  (`tools/mvnw.mjs` picks `mvnw.cmd` / `./mvnw`). Re-evaluate `@nx/maven` when Java modules multiply.
- Next.js `output: 'standalone'` creates symlinks; on Windows without Developer Mode it fails with
  `os error 1314`. Standalone is therefore enabled only via `NEXT_OUTPUT=standalone` (set in the Dockerfile).
- Collector 0.163 renamed exporters to `otlp_grpc`/`otlp_http`; the old `otlp`/`otlphttp` names are deprecated.
- Spring Boot 4 OTLP properties are `management.opentelemetry.{tracing,logging}.export.otlp.endpoint`
  (checked in the `spring-boot-opentelemetry` configuration metadata). Log export needs the Logback OTel appender.
- Docker Compose v5 `up --wait` treats a one-shot service that exits 0 (Flyway) as successful.
- Centrifugo v6.10.2 config keys checked with `centrifugo defaultconfig -d` / `defaultenv`:
  `client.subscription_token.enabled` + `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`, `channel.namespaces`
  (namespace defaults deny client subscribe/publish), `http_api.key`. Server API: `POST /api/publish` with `X-API-Key`.
- `centrifuge` SDK reports a server-side subscription denial (code 103) as an `unsubscribed` event, not `error`.
- PostgreSQL 18 provides `uuidv7()`; used as the default for chat ids (time-ordered).

## Updating versions

1. JS: `pnpm nx migrate latest` (Nx and plugins) then `pnpm nx migrate --run-migrations`; other packages with
   `pnpm up <pkg>@<version>`. Commit the lockfile.
2. Java: bump `spring-boot-starter-parent`, `spring-modulith.version`, and keep `otel-logback-appender.version` aligned
   with the OpenTelemetry SDK version Boot manages (`spring-boot-dependencies` → `opentelemetry.version`).
3. Images: change the tag in the Compose file / Dockerfile, run `pnpm verify`, update this table.
4. Never use `latest`; record the new verification date here.
