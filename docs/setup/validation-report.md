# Initial dev setup — validation report

- **Spec:** `specs/initialdevsetup.spec.md`
- **Date:** 2026-10-25
- **Workstation:** Windows 11, PowerShell 5.1 + Git Bash, Docker Desktop 29.9.2 / Compose v5.6.1,
  Node 24.18.0, pnpm 10.11.0 (Corepack 0.35.0), Git 2.54.0, JDK 25.0.3 (`JAVA_HOME` set per process; machine default JDK 27 untouched)
- **Result:** all mandatory acceptance criteria PASS. Final `pnpm verify` run: **27/27 checks PASS, exit 0**
  (full container stack). Two items carry caveats (see §8): Git repository root location and CI not yet executed
  on GitHub.

Legend: **PASS** = executed and output inspected · **FAIL** · **BLOCKED** (external prerequisite missing) · **NOT RUN**.

## 1. Architecture and repository structure

```text
apps/chatdashboardapp            Next.js 16 App Router dashboard (+ Dockerfile, Vitest component tests)
apps/chatwidget                  Lit 3 + Vite widget, demo page, versioned embed entry (+ Dockerfile, nginx.conf)
libs/designsystem                CSS tokens (primitives, semantic light, dark theme) + token validation tests
libs/componentLibrary/react      Button, StatusBadge (React)          — Nx: component-library-react
libs/componentLibrary/web        <cp-status-badge> (Lit)              — Nx: component-library-web
libs/contracts                   OpenAPI 3.1 platform-api.yaml, Redocly config, json-schema/README
services/modulithplatformservice Spring Boot 4.1.2 / Java 25 / Spring Modulith 2.1.2, Maven Wrapper (+ Dockerfile)
database/flywayMigration         Flyway 13.11.1 runner image + V1__create_platform_bootstrap.sql — Nx: flyway-migration
database/postgres-init           01-roles.sh: platform_migrator (DDL owner) / platform_app (DML only)
tests                            Playwright e2e/ + contract/ — Nx: platform-tests
infra                            centrifugo/config.json, observability/{otel-collector,prometheus,tempo,loki}, grafana provisioning + dashboard
specs                            features/ (README + _template), decisions/ (ADR-0001..0004), architecture/, roadmaps/
docs                             architecture.md, setup/{local-development,tool-versions,troubleshooting,validation-report}.md, runbooks/local-stack.md
scripts                          dev-setup.ps1, verify-local.ps1
tools/mvnw.mjs                   cross-platform Maven Wrapper launcher for Nx targets
.github/workflows/ci.yml         CI (frontend affected/full, contracts+compose, backend, E2E on container stack)
docker-compose.yml               postgres, flyway-migration, centrifugo, otel-collector, prometheus, tempo, loki, grafana
docker-compose.apps.yml          modulithplatformservice, chatdashboardapp, chatwidget
root                             nx.json, package.json, pnpm-lock.yaml, pnpm-workspace.yaml, tsconfig.base.json, eslint.config.mjs,
                                 .env.example, .gitignore, .gitattributes, .dockerignore, .editorconfig, README.md, AGENTS.md, CONTRIBUTING.md
```

Diagram and boundaries: [docs/architecture.md](../architecture.md). `projectDashboard` and `bffservice` are documented
there as deferred and do not exist in the workspace or Compose files.

## 2. Versions

Full table with references and compatibility findings: [tool-versions.md](tool-versions.md). Key: Nx 24.0.1, Next.js
16.4.3, React 19, Lit 3.3.3, Vite 8.3.5, Playwright 1.64.1, TypeScript 6.0.3, Spring Boot 4.1.2, Spring Modulith 2.1.2,
Maven 3.10.0 (wrapper), images `postgres:18.6-alpine3.24`, `flyway/flyway:13.11.1-alpine`, `centrifugo/centrifugo:v6.10.2`,
`otel/opentelemetry-collector-contrib:0.163.2`, `prom/prometheus:v3.15.1`, `grafana/grafana:13.2.4`, `grafana/tempo:3.1.1`,
`grafana/loki:3.7.9`, Temurin 25.0.4.1_1, `node:24.22.1-alpine3.24`, `nginxinc/nginx-unprivileged:1.31.7-alpine3.24`.

## 3. Projects created

`nx show projects` → `modulithplatformservice, component-library-react, component-library-web, flyway-migration,
chatdashboardapp, designsystem, chatwidget, contracts, platform-tests` (9 projects).

## 4. Commands executed (exact, in order) and results

### Gate A — inspect
| Command | Result |
|---|---|
| `pwd; ls -la`; `node -v`; `npm -v`; `pnpm -v`; `corepack --version`; `git --version`; `docker --version`; `docker compose version`; `docker info`; `docker compose ls`; `java -version`; `$JAVA_HOME`; port scan (3000,3001,3100,3200,4317,4318,5173,5432,8000,8080,9090) | PASS — folder held only `specs/` + `roadmap/`; all ports free; JDK 25.0.3 found alongside default JDK 27 |

### Gate B/C — scaffold
| Command | Result |
|---|---|
| `npx create-nx-workspace@24.0.1 chat-platform --preset=apps --workspaces=false --pm=pnpm --nxCloud=skip --skipGit --formatter=prettier --linter=eslint --aiAgents=none --analytics=false --interactive=false` (in scratch dir, copied into the repo folder without `node_modules`) | PASS |
| `pnpm nx add @nx/next`, `@nx/react`, `@nx/vite`, `@nx/web`, `@nx/playwright`, `@nx/eslint` | PASS |
| `pnpm nx g @nx/next:application apps/chatdashboardapp …`, `@nx/react:library libs/componentLibrary/react …`, `@nx/js:library libs/componentLibrary/web …`, `@nx/js:library libs/designsystem …`, `@nx/web:application apps/chatwidget --bundler=vite …` | PASS |
| `pnpm add next@16.4.3 lit@3.3.3`; `pnpm add -D eslint-config-next@16.4.3 @playwright/test@1.64.1 @redocly/cli yaml ajv ajv-formats eslint-plugin-playwright` | PASS |
| `curl https://start.spring.io/starter.zip -d bootVersion=4.1.2 -d javaVersion=25 -d dependencies=web,actuator,jdbc,postgresql,modulith,prometheus,opentelemetry,testcontainers …` | PASS |
| `docker pull` of all 12 pinned images | PASS |

### Gate D/E — build, test, run
| Command | Result |
|---|---|
| `powershell -File scripts/dev-setup.ps1 -SkipInstall` (twice) | PASS — first run created `.env`; second run: "`.env already exists - left unchanged`" |
| `JAVA_HOME=jdk-25.0.3 ./mvnw -B test` (services/modulithplatformservice) | PASS — 6 tests: ModularityTests 1, StatusControllerTests 3, BootstrapIntegrationTests 2 (Testcontainers PostgreSQL 18, **not skipped**) |
| `pnpm nx run-many -t lint` | PASS (6 projects) |
| Boundary probe: temporary files importing React lib from web lib and web lib from dashboard, `eslint` on them | PASS — 3 `@nx/enforce-module-boundaries` errors as intended; probe files deleted |
| `pnpm nx run-many -t test --exclude=modulithplatformservice` | first run FAIL (widget input not cleared after send — real Lit dirty-check bug) → fixed with `live()` directive → PASS (designsystem 4, component-library-web 2, component-library-react 3, chatdashboardapp 3, chatwidget 3) |
| `pnpm nx run-many -t typecheck` | PASS (6 projects) |
| `pnpm nx run-many -t build` | first run FAIL ×2: `'mvnw.cmd' is not recognized` (fixed launcher to `.\mvnw.cmd`) and Next standalone `os error 1314` on Windows (standalone now only in Docker build) → PASS (Next.js, Vite incl. `embed/chat-platform-widget.v1.js`, Maven package) |
| `pnpm nx run contracts:validate` | first run 2 warnings → rules justified in `redocly.yaml` → PASS, 0 warnings |
| `docker compose config -q`; `docker compose -f docker-compose.yml -f docker-compose.apps.yml config -q` | PASS |
| `docker compose up -d --wait` | PASS — postgres/centrifugo/prometheus/grafana healthy, flyway-migration `Exited (0)`, "Successfully applied 1 migration … now at version v1" |
| `pnpm db:info`; `pnpm db:validate`; `pnpm db:migrate` | PASS — info shows V1 Success; "Successfully validated 1 migration"; "Schema "platform" is up to date" |
| `psql` as `platform_app`: `select … from platform_bootstrap`; `create table platform.hack`; `drop table platform_bootstrap` | PASS — select returns `local-poc`; DDL denied (`permission denied for schema platform`, `must be owner of table`) |
| Mode A: `pnpm dev:backend` (JDK 25), `pnpm dev:widget`, `pnpm dev:dashboard`; `curl` health/status/bootstrap | PASS — all 200; status `UP` with database `schema version 1` and centrifugo UP; `X-Trace-Id` header present |
| `pnpm e2e` (Mode A) | first runs FAIL: Nx-inferred web-server deps clashed with running dev servers (target made explicit), `import.meta` under Playwright CJS (→ `__dirname`), two ambiguous locators → PASS **14/14** |
| Mode B: `docker compose -f docker-compose.yml -f docker-compose.apps.yml up --build -d --wait` | PASS — 4 images built; backend/dashboard/widget healthy; backend runs as `uid=100(app)`; 0 Flyway/migration lines in backend log |
| `pnpm e2e` (Mode B) | PASS **14/14** |
| Telemetry checks (curl Prometheus targets/query, Tempo `/api/v2/traces/<id>`, Loki `query_range` with `trace_id`, Grafana `/api/datasources/uid/*/health`) | PASS — see §6 |
| Fresh start: `docker compose -f … down` (no `-v`), `up -d --wait`, `restart modulithplatformservice` | PASS — volumes kept; `flyway_schema_history` unchanged (1 row, same `installed_on`); bootstrap row still returned |
| `docker run rhysd/actionlint:1.7.12 .github/workflows/ci.yml` | PASS (exit 0, incl. shellcheck) |
| `pnpm dev` (all three via Nx) + `pnpm e2e` | PASS — all ports 200, E2E 14/14 |
| `pnpm verify` run 1 | FAIL 26/27 — script bug (PS 5.1 returns actuator's vendor media type as bytes); proves non-zero exit on failure |
| `pnpm verify` run 2 | FAIL 26/27 — Loki `/ready` briefly 503 after restart; readiness probes now retry ≤30 s |
| `pnpm verify` run 3 (final) | **PASS 27/27, exit 0** |

## 5. Acceptance criteria matrix

### Repository
| Criterion | Result | Evidence |
|---|---|---|
| Nx workspace valid; apps/libs in `nx show projects` | PASS | 9 projects listed |
| pnpm lockfile exists; frozen install succeeds | PASS | `pnpm install --frozen-lockfile` in verify |
| Boundary tags/rules prevent cross-app and React/Lit coupling | PASS | boundary probe → 3 lint errors |
| Project/folder names match docs | PASS | README layout + architecture table (Nx kebab-case names documented) |
| AGENTS.md, CONTRIBUTING.md, ADRs, feature template exist | PASS | ADR-0001..0004, `specs/features/_template/feature.spec.md` |

### Applications
| Criterion | Result | Evidence |
|---|---|---|
| Next.js dashboard builds and loads in browser | PASS | `next build`; Playwright Chromium in Mode A and B |
| Lit widget builds; custom element renders | PASS | `vite build`; E2E "renders the custom element and greeting" |
| Shared tokens consumed by dashboard and widget | PASS | both import `@chat-platform/designsystem/tokens.css`; E2E shadow-DOM token test |
| React + web-component libraries each have a working example | PASS | unit tests; StatusBadge/Button on dashboard; `<cp-status-badge>` on widget page |
| Spring Boot builds with JDK 25 and starts | PASS | Maven package/test on 25.0.3; container on Temurin 25 |
| `/actuator/health`, `/api/v1/status`, `/api/v1/bootstrap` valid | PASS | verify + contract tests against OpenAPI schemas |
| `/api/v1/bootstrap` reads the Flyway record | PASS | `setupKey: local-poc`, integration test + live |
| `projectDashboard` / `bffservice` deferred, not required | PASS | documented in architecture.md only |

### Database and infrastructure
| Criterion | Result | Evidence |
|---|---|---|
| PostgreSQL starts healthy | PASS | `pg_isready` healthcheck |
| Flyway independently runnable: info/validate/migrate | PASS | `pnpm db:*` |
| Backend does not migrate on startup | PASS | no Flyway on runtime classpath; app role DDL denied; history unchanged after restart |
| Centrifugo local-only config + verified readiness | PASS | `/health` healthcheck (wget in image) and backend status; secrets from `.env`; admin disabled; explicit origins |
| Observability containers start; Grafana reachable; datasources provisioned | PASS | Grafana `/api/health`; 3 datasource health = OK; dashboard folder provisioned |
| Backend metrics queryable in Prometheus/Grafana | PASS | `up{job="modulithplatformservice"}=1`; `http_server_requests_seconds_count` by uri |
| Trace and log pipelines verified end-to-end | PASS | trace id from `X-Trace-Id` found in Tempo; log line with same `trace_id` found in Loki |
| Compose config valid (base and full) | PASS | `config -q` both |

### Tests and developer experience
| Criterion | Result | Evidence |
|---|---|---|
| Lint, typecheck, tests, builds pass | PASS | verify run 3 |
| OpenAPI contract validation | PASS | Redocly 0 warnings + live Ajv validation |
| Playwright E2E smoke | PASS | 14/14 (Mode A twice, Mode B twice) |
| Root verify: non-zero on failure, zero on success | PASS | runs 1–2 exit 1, run 3 exit 0 |
| Setup/verify scripts repeatable, don't overwrite `.env` or destroy volumes | PASS | dev-setup rerun leaves `.env`; scripts contain no `down`/`volume rm` |
| Docs accurate (commands, URLs, ports, troubleshooting, reset warnings) | PASS | all README commands were executed in this session |
| CI workflow exists and is validated | PASS (static) | actionlint clean; commands mirror locally executed ones. **Not executed on GitHub** (no remote) |
| No secrets committed | PASS | `.env` git-ignored; verify scans bundles and all committable files for `.env` secret values → none. Nothing was committed (no commit requested) |

## 6. Local URLs and where to look

| What | Where |
|---|---|
| Dashboard / widget demo | http://localhost:3000 · http://localhost:5173 |
| API | http://localhost:8080/api/v1/status · /api/v1/bootstrap · /actuator/health · /actuator/prometheus |
| Grafana | http://localhost:3001 → Dashboards → Chat Platform → *Chat Platform - Local Overview*; Explore → Tempo/Loki |
| Prometheus | http://localhost:9090 (Status → Targets; `max(up{job="modulithplatformservice"})`) |
| Loki / Tempo / Collector | http://localhost:3100 · http://localhost:3200 · http://localhost:13133 |
| Centrifugo | http://localhost:8000/health |
| Logs | `docker compose logs -f <service>`; Loki `{service_name="modulithplatformservice"} \| trace_id="<X-Trace-Id>"` |

## 7. Credentials policy

Secrets exist only in the git-ignored `.env`, generated per machine by `scripts/dev-setup.ps1` (random hex, never
printed). `.env.example` contains only `__generate__` placeholders. PostgreSQL uses three roles (admin/migrator/app);
Grafana admin password and Centrifugo keys are local-only. No secret values appear in this report.

## 8. Known limitations and items before UAT/production

1. **Git root:** the existing repository root is `D:\BYTEFLOCK` (no commits), not `D:\BYTEFLOCK\saasplatform`.
   The workspace works as a subfolder, but GitHub Actions only reads `.github/workflows` at the repo root and
   `ci.yml` assumes `saasplatform` is the root. Decide: make `saasplatform` its own repository, or move/adapt the
   workflow (`working-directory`). Nothing was committed.
2. CI is statically validated (actionlint) but has not run on GitHub; `codeql-or-security.yml` not added (add CodeQL
   once the repo is on GitHub).
3. No authentication/authorization, tenancy, or Centrifugo channel/token design. Centrifugo must not be exposed
   until the first real-time spec defines tenant-scoped channels and token issuing.
4. Observability backends and actuator Prometheus endpoint are unauthenticated (loopback-only locally). UAT/prod
   needs auth/TLS, separate management port or network policy, retention and object storage.
5. Prometheus lists both host and container backend targets; one is DOWN in Mode A (dashboards use `max(up)`).
6. Host default JDK is 27; validation used JDK 25 via per-process `JAVA_HOME`. Builds also succeed on 27.
7. Next.js `standalone` output only in Docker (Windows symlink privilege). Host `next start` uses regular output.
8. On Windows, stopping `pnpm dev` other than with Ctrl+C in its terminal can leave child node/java processes
   holding ports (see troubleshooting: port conflicts).
9. Your user-level `~/.m2/settings.xml` adds Redgate/GitHub repositories that return HTTP 401 warnings; harmless.
10. Single-node, in-memory Centrifugo engine; Loki/Tempo on local filesystem; no backups — local only.
11. No container image signing/SBOM/publishing yet (extension points noted in `ci.yml`).
12. Flyway used in tests (12.4.0, Boot-managed) differs in minor version from the runner image (13.11.1).

## 9. Next step

1. Create `specs/features/<feature-name>/feature.spec.md` from `specs/features/_template/feature.spec.md`
   (e.g. `specs/features/website-widget-configuration/feature.spec.md`) with acceptance criteria, OpenAPI changes,
   migration impact and tenant/threat considerations.
2. Ask the agent: *"Implement `specs/features/website-widget-configuration/feature.spec.md` following AGENTS.md."*
3. The agent updates the contract → adds `V2__…sql` → adds a backend module → UI → tests, then runs `pnpm verify`.
