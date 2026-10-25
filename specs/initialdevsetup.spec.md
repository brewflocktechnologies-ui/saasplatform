# Initial Development Setup Specification — `initialdevsetup.spec.md`

**Document type:** Executable setup specification for an AI coding agent  
**Status:** Ready for execution  
**Target:** Empty local folder → working Nx monorepo → reproducible local POC → verified development foundation for the first feature specification  
**Primary workstation:** Windows with PowerShell, Docker Desktop, JDK 25 and Node.js already installed  
**Primary goal:** Scaffold the repository, implement the smallest meaningful vertical slice, run it locally with Docker Compose, validate the result, and leave the repository ready to receive and implement the first product feature spec.

---

## 0. Instructions to the AI coding agent — read this first

You are the implementation agent. Examples of suitable agents include Claude Code and other coding agents that can inspect files, run shell commands, edit code and execute tests.

**Do not merely explain how to perform this setup. Perform it in the current working directory.** Read this entire specification first, inspect the actual environment, create the files, install project-local dependencies as required, run the applications, execute the tests and repair failures. Continue until all mandatory acceptance criteria that can be validated in the current environment pass.

### Operating rules

1. Treat this file as the initial setup specification and source of truth for this task. Translate it into actual files, commands, runnable applications, tests and documentation.
2. Work in the current directory. First run `pwd` and list files. Do not create a nested second repository unless the current directory is not the intended workspace. The starting folder may contain only this specification.
3. Do not ask the user to do routine work that you can do directly. Do not pause after producing a plan. Execute the setup.
4. Inspect the installed toolchain before choosing versions. The user already has Docker Desktop running, JDK 25 and Node.js installed. Do not install a second global toolchain unnecessarily.
5. Research current official documentation whenever a version, generator, image tag, configuration option or framework compatibility is uncertain. Use stable releases; avoid prereleases unless there is a compelling, documented reason. Record chosen versions and the verification date in `docs/setup/tool-versions.md`.
6. Do not blindly use `latest` for base images or long-lived dependencies. Choose mutually compatible stable versions, pin concrete image tags, and record version-update instructions. The one-time workspace generator can use the current stable CLI, but capture the resulting exact versions in lockfiles.
7. Choose the simplest working configuration that meets the requirements. Prefer supported framework generators and documented configuration over hand-invented build scaffolding. Inspect generator help for the versions actually installed.
8. Make safe, reversible changes. Do not run destructive commands such as `git reset --hard`, delete arbitrary user files, remove Docker volumes, or run `docker compose down -v` unless explicitly authorized. Do not change machine-wide configuration.
9. Never commit secrets. Create `.env.example`; create a local ignored `.env` with random local-only secrets if required. Do not use production credentials. Never expose secret values in logs or handover notes.
10. Do not claim that a check passed unless you actually ran it and inspected the result. Distinguish **PASS**, **FAIL**, **BLOCKED** and **NOT RUN** in the final report.
11. If a proposed command or config fails due to version drift, consult the installed tool's help and current official documentation, correct it and retry. Do not repeatedly run the same failing command without changing the cause.
12. Keep the POC intentionally small. Do not implement full live-chat product features, real authentication, tenant provisioning, AI agents, billing, a BFF, or a production Kubernetes platform in this setup task.
13. Keep `projectDashboard` and `bffservice` optional. Record their intended location and boundaries in documentation, but do not add empty services to the default runtime or pretend that they are implemented.
14. When done, the repository must be in a coherent state that another developer or agent can clone, understand, run, test and extend using the repository documentation.

### Required execution sequence

- **Gate A — Inspect:** inspect the workspace and available tools.
- **Gate B — Decide:** verify compatibility and record architecture/version decisions.
- **Gate C — Scaffold:** create Nx, frontend apps, shared libraries, Spring Boot service, migration runner, contracts, tests, Compose and observability configuration.
- **Gate D — Run:** start the dependency stack, migrate the database, start the apps (host development mode and container mode where feasible).
- **Gate E — Verify:** execute builds, unit tests, contract validation, E2E smoke tests, health checks and telemetry checks.
- **Gate F — Handover:** create runbooks and a report showing exact commands, URLs, results, limitations and next steps.

Do not stop at a phase boundary simply because files exist. Proceed through the validation gates.

---

## 1. Product and architecture context

This repository is the foundation for an extensible, multi-tenant SaaS customer-support/live-chat platform. It must stay vendor-agnostic, container-first, independently deployable and suitable for later operation on local development machines, test/UAT environments, cloud infrastructure, VMs or bare-metal servers.

### Required projects

| Project | Technology | Required in this setup? | Responsibility |
|---|---|---:|---|
| `chatdashboardapp` | React + Next.js + TypeScript | Yes | Customer/operator dashboard; local POC home page and service status view |
| `chatwidget` | Lit Web Components + TypeScript + Vite | Yes | Framework-independent embeddable widget demo and component build |
| `projectDashboard` | Select a frontend only when needed | No — defer | Optional future project/engineering dashboard; document, do not deploy an empty app |
| `designsystem` | CSS custom properties/tokens, TypeScript exports only if needed | Yes | Primitive and semantic design tokens, typography, themes and foundation guidance |
| `componentLibrary` | React components and Lit/web components in separate entry points | Yes | Small in-house accessible component examples, with no cross-framework runtime coupling |
| `modulithplatformservice` | Java 25-compatible Spring Boot + Maven | Yes | Modular monolith; expose a minimal health/bootstrap API and database-backed proof of connectivity |
| `flywayMigration` | Dedicated Flyway CLI migration container | Yes | Versioned DDL, migration validation and execution, separate from app startup |
| `bffservice` | Not selected/implemented yet | No — defer | Optional future BFF when the dashboard needs a distinct aggregation/security boundary |
| PostgreSQL | Official PostgreSQL image | Yes | Local relational database using a persistent Docker volume |
| Centrifugo | Official Centrifugo image | Yes | Local real-time infrastructure service, independently configured and containerized |
| Observability | OpenTelemetry Collector, Prometheus, Grafana, Tempo and Loki | Yes | Local metrics, traces, logs and dashboards |
| E2E and contracts | Playwright, OpenAPI, JSON Schema; AsyncAPI when needed | Yes | Prove the scaffold and public contracts work |

### Architecture rules

- Nx is the workspace/task orchestrator; it is not a replacement for Maven, Vite, Next.js, Flyway or Docker Compose.
- Use `pnpm` for the JavaScript/TypeScript workspace. Commit `pnpm-lock.yaml`.
- Use Maven Wrapper (`mvnw` / `mvnw.cmd`) for Java reproducibility. Do not require a global Maven installation when the wrapper can be generated.
- Keep the Spring Boot backend as a **modular monolith**. Its first version should have a clear package/module boundary and a small public API; do not split it into microservices.
- `flywayMigration` is a separate one-shot job/image. The runtime Spring Boot application must not execute schema migrations during normal startup.
- Build each deployable application into its own image. Local development may also run Next.js/Vite/Spring Boot directly on the host for hot reload; document both modes. Infrastructure dependencies must run with Compose.
- Use OCI-compatible images and environment-driven configuration. Avoid cloud SDKs or vendor-specific services in the application foundation.
- Do not introduce NATS or another message broker in this setup. Centrifugo is the required real-time server; PostgreSQL is the only required application database.
- Never share the React component runtime with Lit components. Share tokens and documented design decisions, not framework-specific implementations.

---

## 2. Before scaffolding: inspect and validate the toolchain

Run and record the output of the applicable commands. Use PowerShell syntax for Windows commands; if running in WSL/Linux, use equivalent commands and document the shell.

```powershell
Get-Location
Get-ChildItem -Force
node --version
npm --version
corepack --version
pnpm --version
git --version
docker --version
docker compose version
java -version
$env:JAVA_HOME
```

If `pnpm` is unavailable, use Corepack or the official pnpm installation instructions. Do not install pnpm globally through an unpinned script if the package-manager field/Corepack approach is supported by the current Node installation.

Verify Docker is reachable:

```powershell
docker info
docker compose ls
```

If Git is available and the folder is not already a repository, initialize it. Do not overwrite an existing `.git` directory.

### Version selection policy

- Check the current Nx, Next.js, Lit, Spring Boot, Spring Modulith (if used), Flyway, Centrifugo, OpenTelemetry Collector, Prometheus, Grafana, Tempo and Loki documentation.
- Select stable versions compatible with **JDK 25** and the installed Node.js runtime. If the installed Node release is not supported by the selected Nx/toolchain, do not silently force an unsupported setup: choose a documented supported Node LTS for the repository and explain the local compatibility issue. Prefer an existing version manager or containerized toolchain over changing the machine-wide Node installation.
- Confirm the Spring Boot and Spring Modulith release compatibility before adding Spring Modulith. If they do not have a mutually compatible stable release, keep clean package/module boundaries and document Spring Modulith integration as a follow-up rather than forcing incompatible dependencies.
- Prefer Docker image tags with explicit versions. Do not use unpinned `latest` tags in the committed Compose files.
- Run `docker compose config` after authoring the Compose files to validate their merged configuration.

Create `docs/setup/tool-versions.md` containing the chosen versions, official links, compatibility findings and the date verified. Do not put secrets or full environment dumps in this file.

---

## 3. Required target repository layout

Implement a practical subset of the following structure. Keep names consistent. Small differences required by framework generators are acceptable, but explain any differences in the architecture document.

```text
.
├── apps/
│   ├── chatdashboardapp/           # Next.js app and Dockerfile
│   └── chatwidget/                 # Lit + Vite app and Dockerfile
├── libs/
│   ├── designsystem/
│   │   ├── tokens/                 # primitives, semantic tokens, themes
│   │   └── README.md
│   ├── componentLibrary/
│   │   ├── react/                  # accessible React controls
│   │   ├── web/                    # Lit/Web Components
│   │   └── README.md
│   └── contracts/
│       ├── openapi/platform-api.yaml
│       ├── json-schema/
│       └── README.md
├── services/
│   └── modulithplatformservice/
│       ├── pom.xml
│       ├── mvnw
│       ├── mvnw.cmd
│       ├── src/main/java/...
│       ├── src/main/resources/...
│       ├── src/test/java/...
│       └── Dockerfile
├── database/
│   └── flywayMigration/
│       ├── project.json
│       ├── Dockerfile
│       ├── conf/                   # only if needed
│       └── sql/V1__bootstrap.sql
├── tests/
│   ├── e2e/                        # Playwright smoke tests
│   ├── contract/
│   └── README.md
├── infra/
│   ├── observability/
│   │   ├── otel-collector.yaml
│   │   ├── prometheus.yml
│   │   ├── tempo.yaml
│   │   ├── loki.yaml
│   │   └── grafana/provisioning/...
│   └── README.md
├── specs/
│   ├── features/                   # first feature spec goes here
│   ├── architecture/
│   ├── decisions/                  # ADRs
│   └── roadmaps/
├── docs/
│   ├── setup/
│   │   ├── local-development.md
│   │   ├── tool-versions.md
│   │   ├── validation-report.md
│   │   └── troubleshooting.md
│   ├── runbooks/
│   └── architecture.md
├── scripts/
│   ├── dev-setup.ps1
│   └── verify-local.ps1
├── .github/workflows/
│   ├── ci.yml
│   └── codeql-or-security.yml       # only if safely configured and practical
├── .editorconfig
├── .gitignore
├── .dockerignore
├── .env.example
├── AGENTS.md
├── CONTRIBUTING.md
├── README.md
├── docker-compose.yml              # infrastructure services
├── docker-compose.apps.yml         # app containers / full-stack mode
├── nx.json
├── package.json
├── pnpm-workspace.yaml
├── pnpm-lock.yaml
└── tsconfig.base.json
```

Do not create fake empty implementation files simply to satisfy this tree. If a folder is not needed until the first feature, include a short README explaining when it will be created.

---

## 4. Nx and package-manager setup

1. Create an **integrated Nx workspace** in the current directory using the current stable generator and `pnpm`.
2. Avoid an interactive CLI flow where possible; use documented non-interactive flags for the actual installed version. If flags have changed, inspect `--help` rather than guessing.
3. Set the workspace package name to `chat-platform` or another clear name and use TypeScript throughout the frontend workspace.
4. Configure project naming conventions and tags for applications, libraries, contracts and test projects. Define appropriate module-boundary rules so apps cannot import another app's implementation and React-only code cannot be imported by the Lit package.
5. Configure root scripts for common tasks, for example `dev`, `build`, `test`, `lint`, `typecheck`, `e2e`, `contracts:validate`, `infra:up`, `infra:down`, `stack:up`, `stack:down` and `verify`. Ensure these scripts refer to real targets that have been tested.
6. Commit the dependency lockfile and pin the package manager in `package.json` using the correct version actually installed.
7. Use the Nx project graph and `nx show projects` as a validation step.
8. Configure Nx tasks so they can call Maven Wrapper targets for the Java service and Docker Compose/Flyway targets for migrations. `@nx/maven` may be used only after checking its current stability status and workspace compatibility. If it is not appropriate, use documented `nx:run-commands` targets; Maven remains the source of truth for Java builds.
9. Use supported ESLint/TypeScript configuration generated for the selected Nx/Next.js version. Do not combine conflicting lint systems or leave obsolete scripts that fail.

The following commands are examples, not immutable generator syntax. Adapt to the version selected and record the final, exact commands in `README.md`:

```powershell
pnpm dlx create-nx-workspace@latest
pnpm nx --version
pnpm nx show projects
pnpm nx graph
```

The final repo must work from a fresh checkout with the documented install command, not depend on globally installed Nx.

---

## 5. Build the minimal working applications

### 5.1 `chatdashboardapp` — Next.js

Create a real Next.js App Router application in TypeScript with a production build and Dockerfile.

The initial page must be a simple, modern developer dashboard with:

- Product title: **Chat Platform — Local Development**.
- A small architecture/service overview.
- Service status indicators for the backend API, database readiness (through a backend status endpoint), and Centrifugo availability where a browser-safe health check is supported.
- A link or embedded view to the Lit widget demo.
- A display of environment (`local`) and a note that this is scaffold validation, not a production admin dashboard.
- Clear loading, success and failure states; no fake green health indicators when the check has not succeeded.
- Responsive behavior and accessible labels.

Create a minimal React component-library example (e.g., `Button` and `StatusBadge`) and use it on the page. Load styling through shared design tokens. Do not build a full dashboard or authentication framework in this task.

Configuration:

- The browser-facing API base URL must be environment-driven, with a local default such as `http://localhost:8080`.
- Do not use a Docker service hostname in browser-exposed configuration; `http://modulithplatformservice:8080` is not resolvable by the user's host browser.
- Provide a working production build and container start command.
- Ensure the selected Next.js release's supported Node version and container runtime are documented.

### 5.2 `chatwidget` — Lit + TypeScript

Create a Lit Web Component project built with Vite. It must have a real custom element, e.g. `<chat-platform-widget>`.

Requirements:

- A demo page that loads the component in a browser and explains it is a local POC.
- The widget renders a small accessible chat panel with a greeting, message list and input/composer UI.
- Sending a message updates the in-memory demo UI. Do not imply it is persisted to the server.
- Use shared CSS design tokens. Shadow DOM styles must intentionally consume the tokens (CSS custom properties cross a shadow boundary when inherited from the host/document; test this in the browser).
- Add a separate `componentLibrary/web` example such as a `cp-status-badge` or small accessible control and show that it can be imported and rendered independently of React.
- Generate a production bundle and a working Dockerfile/static-serving strategy.
- Add a documented, versioned custom-element registration entry point suitable for future script-tag embedding. Do not yet implement a CDN deployment pipeline.
- Do not put secrets in the widget bundle and do not claim frontend-only chat is production-ready.

Use Lit's official starter/guidance or the supported project generator. Keep the output framework-independent.

### 5.3 `designsystem`

Create CSS token source files and a documented export path, at minimum:

- Primitive tokens: raw palette, base spacing and typography scales.
- Semantic tokens: background, surface, text, muted text, border, primary, success, warning, danger, focus ring.
- Typography tokens and safe fallback stacks. If using Inter, self-host appropriately licensed assets or use a robust system fallback; do not require a third-party font CDN to render the app.
- Spacing, radius, border and focus tokens.
- Light theme; a dark theme may be included if it does not delay the working setup.
- A README documenting naming, backwards-compatible changes, theme overrides and accessibility contrast checks.

Components must consume semantic tokens, not repeat hard-coded color values. Do not introduce runtime token generation or a design-token vendor dependency for this POC.

### 5.4 `componentLibrary`

Create separate React and web-component entry points. Include at least one small reusable component in each framework, with basic tests and usage notes. Keep package exports explicit. Do not couple the React library to the widget or make the web library depend on React.

### 5.5 `modulithplatformservice` — Spring Boot

Create a Java 25-compatible Spring Boot Maven project with a small, intentional package/module structure. At minimum implement:

- `GET /actuator/health` — Spring Boot health endpoint.
- `GET /api/v1/bootstrap` — a simple JSON response read from a table created by Flyway, proving the application can reach PostgreSQL and the migration has run.
- `GET /api/v1/status` — app name/version/environment and safe dependency status information; do not expose secrets or internal stack traces.
- Structured logs and request correlation/trace IDs where practical.
- Actuator metrics, with Prometheus scrape support enabled only for the local/test setup or safely protected in other environments.
- A local CORS policy for the dashboard and widget demo origins; do not use unrestricted CORS as a shortcut.
- Unit and integration tests, including the database-backed bootstrap endpoint using a disposable PostgreSQL test container or another reproducible integration-test strategy. If Testcontainers cannot run in the current environment, still implement the test and mark the environment-specific execution result accurately.

Implement a minimal module layout, for example:

```text
com.example.chatplatform
├── bootstrap/       # bootstrap endpoint and repository for setup proof
├── health/          # status reporting
└── shared/          # only minimal cross-cutting primitives
```

Do not create a generic `utils` dumping ground. Domain modules such as tenancy, websites, conversations and messaging will be added against the first product feature spec.

Use Spring Modulith only if a currently stable release is compatible with the selected Spring Boot version and JDK 25. Otherwise, preserve explicit package boundaries and document the decision. The app must be buildable with Maven Wrapper from Windows PowerShell and inside Docker.

### 5.6 `flywayMigration` — independent migration runner

Create a dedicated migration directory and container using the official Flyway CLI image, separate from the Spring Boot application image.

At minimum:

- Add one immutable bootstrap migration, e.g. `V1__create_platform_bootstrap.sql`.
- Create a small `platform_bootstrap` table and insert one documented local setup record.
- Configure DB URL, username, password and locations with environment variables.
- Provide Nx targets or scripts for `info`, `validate` and `migrate`.
- The runner waits for PostgreSQL to become healthy and exits non-zero if migration validation or execution fails.
- The Spring Boot runtime process does not have DDL owner permissions and does not run Flyway on startup.
- Include a documented dev-only reset approach if needed, but do not automatically drop volumes or schemas as part of normal startup.

Do not edit a migration after it has run in shared environments; add a new versioned migration instead.

### 5.7 Optional placeholders

Document, but do not implement/deploy, `projectDashboard` and `bffservice`. In `docs/architecture.md`, state their intended future boundaries and the conditions that justify introducing them. The default stack must not fail because either is absent.

---

## 6. API-first contracts and specification-driven development foundation

Create committed contracts before or alongside their implementations.

### OpenAPI

Add `libs/contracts/openapi/platform-api.yaml` defining at least:

- `GET /api/v1/status`
- `GET /api/v1/bootstrap`
- Error response shape and content type
- Correct response schemas and examples

Ensure the actual Spring Boot endpoints conform to the contract. Add an automated syntax/schema validation command to CI and local verification. Avoid generating a client unless it can be reproducibly generated and is useful for the initial dashboard status view.

### JSON Schema and event contract readiness

Add a `libs/contracts/json-schema/README.md` explaining how future chat message/event schemas are introduced, versioned and validated. Add one small example schema only if it represents a useful generic contract rather than prematurely fixing the entire product domain.

Create `AsyncAPI` in the first feature that introduces real asynchronous business events. Do not invent event contracts prematurely just to fill a folder.

### First feature specification workflow

Create `specs/features/README.md` with a template and required steps:

1. Problem and desired outcome.
2. Actors, scope and explicit non-goals.
3. User stories and acceptance criteria.
4. Data model and migration impact.
5. API/event contract changes.
6. Tenant authorization and threat considerations.
7. UI states and accessibility.
8. Test plan and observable success criteria.
9. Rollout, compatibility and rollback plan.
10. ADR references and handover checklist.

Create `specs/features/_template/feature.spec.md` that future work can copy. The setup agent must not implement a substantive chat product feature until a separate feature spec is provided.

---

## 7. Docker Compose — required local infrastructure

Create `docker-compose.yml` for dependencies/observability and `docker-compose.apps.yml` for optional full-container app execution. Use Compose service DNS names for container-to-container communication, health checks, named volumes for stateful data, and explicit ports. Avoid `container_name` unless there is a demonstrated need.

### Required local services

1. PostgreSQL.
2. Centrifugo.
3. OpenTelemetry Collector.
4. Prometheus.
5. Grafana.
6. Tempo.
7. Loki.

The apps may run on the host in hot-reload mode, or in the second Compose file as independent containers. Both modes must be documented. The goal is to allow a developer to start infrastructure with one command, run the apps with Nx, and also have a full container-based POC for deployment parity.

### Configuration requirements

- Choose current stable compatible image versions and pin explicit tags (and digests where useful/practical). Record them in `docs/setup/tool-versions.md`.
- Host-bind development ports to `127.0.0.1` when practical so the local database and observability endpoints are not unintentionally exposed to the LAN.
- Use health checks where a service exposes a reliable health/readiness endpoint. `depends_on` without a health condition does not guarantee readiness.
- Use `.env.example` plus a local ignored `.env`. Supply a PowerShell script that creates a local `.env` with random development-only secrets if no local file exists; it must not overwrite an existing `.env`.
- Ensure Grafana is provisioned with Prometheus, Tempo and Loki datasources, so the developer does not have to configure them manually after startup.
- Provision a basic dashboard or document useful queries. At minimum show Spring Boot request/health metrics or target availability. Include an explanation of how to inspect traces and logs.
- Do not expose Grafana, Loki, Tempo, Prometheus or Actuator without authentication/network controls in UAT/prod. The local setup can bind them to loopback for convenience.
- Do not rely on a Docker socket mount for log collection unless it is truly necessary and documented. Prefer an OTLP log pipeline for the POC or a safe platform-appropriate collector strategy.
- Keep telemetry backends optional to application boot only if there is a documented local profile; the default POC setup must include the required observability stack.

### Telemetry pipeline

Implement and verify a real, understandable pipeline:

- Spring Boot exposes scrapeable metrics and/or sends OTLP telemetry.
- OpenTelemetry Collector receives OTLP traces/logs/metrics from any configured application instrumentation and forwards them to the appropriate backend.
- Prometheus scrapes the Spring Boot actuator Prometheus endpoint and the Collector's metrics endpoint.
- Tempo receives OTLP traces.
- Loki receives logs through its supported OTLP ingestion path or an officially documented compatible pipeline.
- Grafana can query Prometheus, Tempo and Loki using provisioned datasource configuration.

Do not configure telemetry exporters with deprecated exporters just because an old example uses them. Verify collector component availability in the chosen collector distribution and the current Grafana/Loki OTLP ingestion configuration.

If frontend tracing is included, configure browser/server exporters carefully and avoid browser access to internal Compose hostnames. It is acceptable for the initial setup to instrument the Spring Boot service first, provided that logs and metrics are real and a trace test is documented.

### Centrifugo configuration

- Run an independently versioned Centrifugo container with configuration supplied from a local config file or environment variables.
- Generate local-only secrets; do not hardcode reusable secrets in source control.
- Define an explicit local allowed-origin list for the app/widget URLs rather than using `*` without explanation.
- Keep admin features disabled unless required for local inspection; if enabled, use a local-only generated password and loopback binding.
- Include a readiness check that the agent verifies against the selected Centrifugo version. If the image lacks a suitable health endpoint, use a documented CLI/API check rather than a made-up URL.
- The POC does not need a production authorization/token service yet. Do not claim that a public demo channel or unprotected client token design is production-safe. Record that application-level channel authorization and token issuing must be implemented with the first real-time feature spec before UAT/production.

### Recommended local ports

Use these if available; if any are occupied, inspect the conflict, select a different port and update all references together. Do not silently leave an app unreachable.

| Service | Default host URL/port |
|---|---|
| Next.js dashboard | `http://localhost:3000` |
| Lit/Vite widget demo | `http://localhost:5173` in host dev mode; a documented alternative in container mode |
| Spring Boot | `http://localhost:8080` |
| PostgreSQL | `127.0.0.1:5432` |
| Centrifugo | `http://localhost:8000` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3001` (use 3001 to avoid the Next.js port) |
| Loki | `http://localhost:3100` |
| Tempo | `http://localhost:3200` for query/API; OTLP receiver on configured ports |
| OpenTelemetry Collector | document enabled OTLP receiver ports, typically `4317` and `4318` |

Do not assume all ports are available. Make the port map authoritative in `.env.example` and `docs/setup/local-development.md`.

---

## 8. Container images and full-stack mode

Provide independent Dockerfiles and test the images for:

- `chatdashboardapp` (Next.js production server, not a development server in the production image).
- `chatwidget` (build the static bundle and serve it from an appropriate small static web server or documented preview server for local POC use).
- `modulithplatformservice` (multi-stage Maven build with a minimal Java runtime image).
- `flywayMigration` (Flyway CLI runner with versioned SQL copied into the image).

Dockerfiles should:

- Use explicit, compatible base image tags.
- Use multi-stage builds where relevant.
- Exclude `.git`, local `.env`, node_modules, build outputs and other unnecessary files through `.dockerignore`.
- Avoid running the runtime process as root where practical.
- Avoid storing credentials in image layers/build args.
- Support clean builds from the repository root, so shared libraries and lockfiles are available to frontend builds.
- Expose only the required application port and provide documented health checks where possible.

`docker-compose.apps.yml` must define full-stack application services and correct startup ordering: PostgreSQL healthy → Flyway migration successful → backend ready → frontend containers. Use `service_healthy` / `service_completed_successfully` where supported. Do not use arbitrary fixed `sleep` calls as the only readiness strategy.

Required modes:

**Mode A — Developer hot reload**

```powershell
docker compose up -d
docker compose ps
pnpm install --frozen-lockfile
pnpm dev
```

`pnpm dev` may run Nx-managed apps and the Java app in separate terminals/processes if one command cannot supervise all long-running tasks reliably. Document the exact supported commands.

**Mode B — Full container POC**

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml config
docker compose -f docker-compose.yml -f docker-compose.apps.yml up --build -d
docker compose -f docker-compose.yml -f docker-compose.apps.yml ps
```

The exact service names may differ, but the equivalent commands must work and be written in the README. Mode B must build and start the independently deployable app images rather than silently running the app on the host.

---

## 9. Test strategy and validation gates

Implement executable checks, not just a written testing strategy.

### Unit and static checks

- Next.js app: lint, type-check, production build and any component tests selected by the framework setup.
- Lit widget: lint, type-check, production build and at least one component test if supported by the chosen starter.
- React/web component libraries: test at least the example components and ensure package exports work.
- Java service: Maven Wrapper test and package; ensure tests do not depend on a developer's local database unless explicitly classified as integration tests.
- Design tokens: validate imports and verify both framework apps build when consuming the tokens.
- Contracts: run OpenAPI syntax/schema validation and check that contract files are included in Git.
- Nx: `nx show projects`, `nx graph` (or a suitable CI check), and a root verification target.

### Integration checks

- PostgreSQL health check passes.
- Flyway `info`, `validate`, and `migrate` execute successfully against the local database.
- Backend starts with the local profile, connects to PostgreSQL and returns the bootstrap row.
- Prometheus can scrape Spring Boot metrics and shows the backend target as `UP`.
- Grafana is reachable and the three data sources are provisioned and healthy where supported.
- A test trace can reach Tempo; logs can be queried in Loki if the local pipeline claims to support log ingestion.
- Centrifugo process is healthy and reachable on the documented local port.
- Frontend pages load and no console/network errors are ignored without investigation.

### E2E checks

Use Playwright for a small browser-level smoke suite. The minimum checks are:

1. The dashboard page opens and shows the correct product title.
2. Its backend status check can display a real healthy result when the backend is running; a failure state appears when the backend is unavailable.
3. The widget demo page opens and renders the custom element and greeting.
4. Sending a demo message updates the widget's in-memory message list.
5. The dashboard can link to/open the widget demo.

Configure a deterministic base URL and a documented web-server lifecycle for tests. In CI, start required services or use the project's supported webServer/container setup. Do not rely on a developer manually opening a browser.

### Security and repository checks

- `.env` and secret files are ignored.
- `.env.example` contains placeholders, not usable credentials.
- No credentials appear in Git history created during this task, source maps or frontend bundles.
- The application runtime database user is not the DDL/migration user where practical. For the POC, create separate least-privilege roles if this can be done reliably without complicating setup; otherwise document the exact follow-up and make the database initialization approach explicit.
- Health/status endpoints do not expose secrets, stack traces or internal configuration values.
- CORS is explicitly configured for local origins.
- Dependency and container versions are pinned and the install is lockfile reproducible.
- `docker compose config` succeeds for both base and merged Compose configurations.

---

## 10. Local developer experience

Create `README.md` and `docs/setup/local-development.md` so a developer can start without reading this specification every time.

The docs must include:

1. Prerequisites and supported toolchain versions.
2. First-time setup command(s), including safe creation of `.env`.
3. Start/stop commands for infrastructure and all apps.
4. Hot-reload versus full-container mode.
5. All service URLs, ports and local-only credentials policy.
6. How to run tests, lint, type-check, builds, migration validation and E2E.
7. How to see logs (`docker compose logs -f <service>`), inspect containers and check health.
8. How to open Grafana, verify datasources, query metrics/logs/traces and inspect application logs.
9. Troubleshooting for port conflicts, Docker not running, wrong Java version, Node compatibility, Windows file permissions/line endings, stale containers and failed Flyway validation.
10. A safe stop command. Do not document `down -v` as the default cleanup command because it deletes local database and observability data.
11. A separate, strongly labelled development-only data reset procedure, if supplied, warning that it destroys volumes.
12. How to create the first feature spec and implement it using the specification-driven workflow.

Create scripts:

- `scripts/dev-setup.ps1`: verify prerequisites, create `.env` only if absent, generate dev-only random secrets, and install dependencies where safe. The script must be rerunnable and idempotent.
- `scripts/verify-local.ps1`: run the relevant static checks, builds, contract validation, Compose config validation, health checks and E2E steps. It must print a clear PASS/FAIL summary and exit non-zero if a required check fails.

If a single root `pnpm dev` cannot start multiple long-running project processes reliably, provide `pnpm dev:dashboard`, `pnpm dev:widget`, `pnpm dev:backend` and a documented terminal workflow rather than a fragile script.

---

## 11. GitHub Actions and delivery readiness

Add a practical `.github/workflows/ci.yml` that runs on pull requests and pushes to the primary branch. It must:

- Check out the repository and set up the chosen Node/pnpm versions.
- Install frontend dependencies using a frozen lockfile.
- Run Nx affected checks when a base SHA is available, with a correct fallback to the full required verification if not.
- Run lint/type-check/tests/builds for affected frontend projects.
- Set up the chosen JDK 25 runtime and run Maven Wrapper tests/package for the backend.
- Validate OpenAPI/contracts.
- Validate the Compose configuration.
- Run E2E tests against an ephemeral or CI-started stack if feasible. If full observability is too heavy for every pull request, keep a smaller PR stack and validate the full stack in a separate workflow/manual check, clearly documented.
- Cache only safe build artifacts/dependencies; don't cache secrets.

Do not implement production deployment workflows, cloud credentials or release publishing in this initial task. Leave clear extension points for image publishing, signing, SBOM generation, and environment promotion.

---

## 12. Spec-driven development and architecture governance

Create these foundational documents:

- `AGENTS.md` — concise repository-wide coding-agent rules.
- `CONTRIBUTING.md` — branch, commit, PR, checks and review expectations.
- `docs/architecture.md` — diagrams/description of apps, libraries, backend, migrations, real-time service, observability and deployment boundaries.
- At least three initial ADRs:
  - `ADR-0001-nx-monorepo.md` — one repository with Nx orchestration and project boundaries.
  - `ADR-0002-modular-monolith.md` — one Spring Boot deployment with internal module boundaries.
  - `ADR-0003-dedicated-flyway-runner.md` — migrations run as a separate job/image.
  - Add `ADR-0004-centrifugo-realtime.md` if there is a meaningful trade-off to record.
- `specs/features/_template/feature.spec.md` — copyable first-feature spec template.
- `docs/runbooks/local-stack.md` — start, stop, inspect and recover the local stack.

Use an ADR format with context, decision, alternatives, consequences, status and date. Do not create ADRs that claim a decision was validated if it was only assumed.

### First feature readiness

At completion, a developer must be able to add a new file such as `specs/features/website-widget-configuration/feature.spec.md`, define its acceptance criteria and API contract, and then ask an agent to implement it against the existing project structure. The agent should not need to reconstruct the repo conventions or invent the build/test workflow.

---

## 13. Final end-to-end validation procedure

Run these checks in order, adapting command names to the actual workspace and recording their exact results.

### A. Workspace and build validation

```powershell
pnpm install --frozen-lockfile
pnpm nx show projects
pnpm nx graph
pnpm nx run-many -t lint
pnpm nx run-many -t test
pnpm nx run-many -t build
```

If a target is not defined for a specific project, configure target availability intentionally or use the root verification target. Do not leave commands in the documentation that fail because the named target does not exist.

Run Maven explicitly too, using the generated Maven Wrapper. From the repository root, use valid PowerShell syntax:

```powershell
Push-Location services/modulithplatformservice
.\mvnw.cmd test
.\mvnw.cmd package
Pop-Location
```

If the wrapper is in a different generated location, update these commands to the actual path and verify them. Ensure the final README contains only commands that were actually tested.

### B. Compose validation and infrastructure startup

```powershell
docker compose config
docker compose -f docker-compose.yml -f docker-compose.apps.yml config
docker compose up -d
docker compose ps
```

Check that required containers are healthy or have a documented readiness check. Inspect logs for failing or restarting containers. Do not proceed by assuming a container is ready just because `docker compose up -d` returned successfully.

### C. Database and migration validation

- Connect to PostgreSQL using the documented local credentials.
- Run Flyway `info` and `validate`.
- Apply migrations and confirm the `platform_bootstrap` table/record exists.
- Restart the backend and confirm the schema is not modified automatically by backend startup.

### D. Run the apps

Test both modes if the workstation has sufficient resources:

- Host hot-reload mode: start backend, dashboard and widget using documented commands.
- Full-container mode: build and start app images using both Compose files.

Confirm all intended URLs load from the host browser. Check browser console output and relevant logs.

### E. Observability validation

- Open Grafana at the documented local URL.
- Verify Prometheus, Tempo and Loki datasource health (as supported by the datasource API).
- Confirm Prometheus scrapes the backend and exposes an `up` series for the target.
- Generate a backend request and show that at least one application metric changes or a request counter/latency metric is queryable.
- Generate a test trace and verify it is visible in Tempo, if trace instrumentation is configured.
- Generate a backend log and verify it is queryable in Loki, if the log pipeline is configured.
- If any signal path is intentionally incomplete, fix it or explicitly mark it as blocked and state the missing component. Do not describe a configured dashboard alone as proof that telemetry flows.

### F. Browser/contract tests

Run OpenAPI validation, application tests, Playwright E2E smoke tests and the complete root `verify` target. Review the outputs and fix failures.

### G. Fresh-start check

Without deleting volumes, stop the stack using the documented non-destructive command, then start it again and check database persistence and application health. Do not run destructive cleanup as a convenience.

---

## 14. Mandatory acceptance criteria

This task is complete only when the agent has checked each item and recorded evidence in `docs/setup/validation-report.md`.

### Repository

- [ ] Nx workspace is valid, with frontend apps/libs discoverable through `nx show projects`.
- [ ] pnpm lockfile exists and a frozen install succeeds.
- [ ] Project boundary tags/rules prevent obvious cross-app and React/Lit coupling.
- [ ] Project names and folder names match the repository documentation.
- [ ] `AGENTS.md`, `CONTRIBUTING.md`, ADRs and the feature-spec template exist.

### Applications

- [ ] Next.js dashboard builds and loads from the host browser.
- [ ] Lit widget builds and the custom element renders in the browser.
- [ ] Shared design tokens are consumed by the dashboard and widget.
- [ ] React and web-component libraries each contain a working example.
- [ ] Spring Boot builds with JDK 25 and starts successfully.
- [ ] `/actuator/health`, `/api/v1/status` and `/api/v1/bootstrap` return valid responses.
- [ ] `/api/v1/bootstrap` reads the record created by Flyway.
- [ ] `projectDashboard` and `bffservice` are explicitly deferred and not required to run the default stack.

### Database and infrastructure

- [ ] PostgreSQL starts and reports healthy.
- [ ] Flyway is independently runnable, and `info`, `validate` and `migrate` work.
- [ ] The backend does not execute schema migrations on normal startup.
- [ ] Centrifugo starts with local-only credentials/configuration and a verified readiness check.
- [ ] Observability containers start; Grafana is reachable and datasources are provisioned.
- [ ] At least real backend metrics are queryable in Prometheus/Grafana.
- [ ] Trace and log pipeline claims are verified end-to-end or reported accurately as blocked/incomplete.
- [ ] Compose config validation passes for base and full-stack modes.

### Tests and developer experience

- [ ] Lint, type-check, tests and production builds pass for all applicable projects.
- [ ] OpenAPI contract validation passes.
- [ ] Playwright E2E smoke checks pass.
- [ ] The root verification command exits non-zero on required check failure and zero on success.
- [ ] Setup/verify scripts are repeatable and do not overwrite `.env` or destroy volumes.
- [ ] The documentation has accurate commands, URLs, ports, troubleshooting and reset warnings.
- [ ] CI workflow exists and at least its YAML/configuration and major commands are validated.
- [ ] No secrets have been committed.

An item may be marked **BLOCKED** only when there is an external, genuinely unavailable prerequisite (for example Docker daemon failure, no network access to fetch packages, or a port conflict that cannot be resolved). Report the evidence and the smallest next action required. Do not label a task blocked because it is inconvenient or because the first command failed.

---

## 15. Final handover report required from the agent

Write `docs/setup/validation-report.md` and summarize it to the user. Include:

1. The resulting architecture and repository structure.
2. Chosen tool/image versions and compatibility references.
3. Files/projects created.
4. Exact commands executed for setup, migrations, builds, tests and runtime.
5. A PASS/FAIL/BLOCKED/NOT RUN matrix for every mandatory acceptance criterion.
6. Local URLs and where to inspect logs/metrics/traces.
7. Any local-only credentials policy without printing secret values.
8. Known limitations and security items that must be addressed before UAT/production.
9. The exact next step: create the first feature spec under `specs/features/` and ask the agent to implement it using that specification.

**Do not claim that the setup is complete unless you have run the relevant commands.** If any item remains blocked, the report must state why and include the last useful diagnostic evidence.

---

## 16. Official references to verify during execution

Use the current versions of these official references; version and configuration details may change. If a URL has moved, locate the current official equivalent and record it in `docs/setup/tool-versions.md`.

- Nx quickstart: https://nx.dev/docs/quickstart
- Nx with Maven: https://nx.dev/docs/technologies/java/maven/introduction
- Next.js installation and system requirements: https://nextjs.org/docs/app/getting-started/installation
- Next.js deployment: https://nextjs.org/docs/app/getting-started/deploying
- Lit getting started: https://lit.dev/docs/getting-started/
- Spring Boot system requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Modulith reference: https://docs.spring.io/spring-modulith/reference/
- PostgreSQL Docker Official Image: https://hub.docker.com/_/postgres
- Flyway documentation: https://documentation.red-gate.com/flyway
- Centrifugo install and Docker Compose: https://centrifugal.dev/docs/getting-started/installation
- OpenTelemetry Collector quickstart: https://opentelemetry.io/docs/collector/quick-start/
- Prometheus getting started: https://prometheus.io/docs/prometheus/latest/getting_started/
- Grafana Docker: https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/
- Grafana Tempo Docker example: https://grafana.com/docs/tempo/latest/docker-example/
- Grafana Loki Docker: https://grafana.com/docs/loki/latest/setup/install/docker/
- Docker Compose: https://docs.docker.com/compose/
- Docker build best practices: https://docs.docker.com/build/building/best-practices/
- OpenAPI specification: https://spec.openapis.org/oas/latest.html
- Playwright: https://playwright.dev/docs/intro

---

## Definition of done

The final result is not a diagram or a collection of placeholder folders. It is a reproducible local developer environment, with the core applications and dependencies running, a separate migration runner, shared tokens and component examples, a working backend-to-database proof, a real observability path, automated validation, independent container builds, and written instructions ready for the first product feature specification.
