# Live chat SaaS: combined delivery roadmap (v2)

Oct 25, 2026 · @Brewflock

## Bottom line

Build the platform on the Claude roadmap's architecture (Spring Boot 4.1 modular monolith, Centrifugo, PostgreSQL 18, Valkey, S3 object storage, Nx 24, Lit widget, Next.js dashboard), keep the ChatGPT roadmap's operational discipline (commit-before-ack, a defined 10k workload, RPO/RTO, a production definition of done), and add the three things neither roadmap has: an OpenSpec spec-driven loop, BDD and TDD at every layer with Gherkin as the executable spec, and a progress dashboard published from CI from the first green build.

What this roadmap commits to:

- **Phase 0, weeks 1–4:** empty folder to a green CI with every quality gate wired and the progress dashboard live. Nothing product-shaped ships before the gates exist.
- **Phase 1, weeks 5–12:** visitor and agent chat end to end, with reconnect recovery proven by a BDD scenario that pulls the network cable.
- **Phase 2, weeks 13–22:** team inbox, agent-assist AI, public demo on a €6 VPS. The Claude roadmap said week 18; four weeks are added for the spec and BDD discipline and for Spring Boot 4 being younger than the agents' training data.
- **Phase 3, weeks 23–32:** knowledge base, AI in draft mode, 10,000-connection soak on one bare-metal k3s box.
- **Phases 4–5, weeks 33–56:** confidence-gated autonomy, enterprise modules, HA production and the open-source launch.

The gates are fixed; the week numbers are estimates with the buffer concentrated in phases 0–2 (22 weeks against the Claude plan's 18). Every phase ends with a live demonstration, and the dashboard shows which OpenSpec changes are proposed, in progress, verified and archived, so progress is visible without asking anyone.

## Analysis of the two roadmaps

The two roadmaps agree on the architecture almost completely and differ on evidence, hosting and the publication path; both are silent on how the team specifies work, tests behaviour and watches progress. The Claude roadmap (24 Oct) is the better source of verified versions, capacity numbers and costs; the ChatGPT roadmap (25 Oct) is the better source of operational discipline and a concrete Compose seed.

| Topic | Claude roadmap | ChatGPT roadmap | Taken into v2 |
| --- | --- | --- | --- |
| Backend | Spring Boot 4.1.2 + Modulith 2.1.1, pinned with sources | Boot 4.1.x, with a 3.5.x fallback | 4.1.x only; 3.5 OSS support ended, so a fallback is not a real option |
| Real-time | Centrifugo v6.10.2, Valkey 9, detailed channel plan, proxies, abuse limits | Centrifugo v6.10.0, Valkey 8, principles only | Claude's channel plan; pin the latest 6.10.x image by digest; Valkey 9.x |
| Publish path | Modulith event registry listener publishes after commit | Explicit outbox table + dispatcher worker | Modulith registry is the outbox; HTTP 201 is the ack and is returned only after commit |
| Ordering | Per-conversation `seq` under row lock, dedupe by `seq` on the client | "Sequence/order key", client dedupe on ids | Claude's `seq`, plus ChatGPT's rule that duplicate publication is possible and must be harmless |
| Modules | 12 modules; audit in `enterprise/` | 14 modules; audit and automation in core | Claude's 12 plus `audit` in core (the table costs nothing); `automation` arrives in phase 3 |
| Tenancy | Shared schema, Hibernate `@TenantId`, RLS with `FORCE`, `SET LOCAL` per transaction | Shared schema, RLS "as defense in depth" | Claude's, verbatim |
| Jobs | db-scheduler on PostgreSQL, ShedLock, `worker` profile | "Background workers", unspecified | db-scheduler; no broker |
| Widget | Lit 3.3 custom element, iframe fallback, 60 KB budget | Web Components + Vite, "evaluate Lit" | Lit, with the budget enforced in CI |
| Functional UAT host | Oracle A1 free or a €6 VPS behind Cloudflare | Vercel ×2 + GCP Cloud Run or VM | Claude's: Vercel Hobby bans commercial use and Cloud Run caps WebSockets at 60 min |
| Scale UAT host | Hetzner AX42-1 dedicated, k3s, ≈€97/mo | GKE Standard regional cluster | Hetzner bare metal; GKE is the drop-in cloud alternative through the same Helm charts |
| Capacity | 2 Centrifugo pods for 20–30k connections, vendor benchmarks cited | Formula with an illustrative 700 connections/node (21 nodes) | Claude's numbers as the plan; ChatGPT's rule that only measured capacity counts |
| Workload definition | 20–30k connections, 333–500 msg/s steady, 1,500 peak | Separate connections, open conversations and active conversations | ChatGPT's framing with Claude's numbers as the UAT profile |
| AI | Spring AI 2, LiteLLM, pgvector hybrid search, Promptfoo, Langfuse, per-conversation costs | Provider-neutral, off the message path, tenant-scoped | Claude's stack; ChatGPT's "AI failure never interrupts chat" becomes a BDD scenario and a gate |
| Agent workflow | AGENTS.md, hooks, worktrees, adversarial cross-vendor review | Bounded tasks, human review of risky areas | Claude's, with OpenSpec as the spec layer it declined to pick |
| Timeline | 52 weeks, dated phases | None | Claude's shape with a 20% buffer |
| Licensing | MIT core + fenced `enterprise/` | Not addressed | MIT core + `enterprise/` |
| 15-factor | Hoffman's list, mapped factor by factor | A custom 15-item list (secrets, telemetry/security) | Hoffman's list, because it is the canonical one |
| Definition of done | Per-PR DoD in AGENTS.md | Production-launch checklist with 15 items | Both: per-PR and per-release |
| Compose seed | Service list only | A working `compose.yaml` (correct PostgreSQL 18 volume path) | ChatGPT's file, with Valkey 9, Centrifugo 6.10.x, Garage, Keycloak and OTel added |

**Where each one is wrong or inconsistent.** The Claude roadmap still carries three leftovers from its earlier Spring-WebSocket design: the monorepo section lists a "Spring Boot WebSocket gateway" deployable, the Gradle wiring adds `spring-boot-starter-websocket`, and the CI section says "Nx remote cache self-hosted" after the same document shows those plugins were deprecated. Its 18-week commercial floor assumes agents write Spring Boot 4 fluently, which its own risk table calls high-likelihood. The ChatGPT roadmap pins no versions, uses a capacity illustration that would mislead a reader into planning 21 nodes, and routes UAT through Vercel and Cloud Run, neither of which fits a commercial WebSocket product.

**What both miss, and what v2 adds.**

- No behaviour-driven tests: Claude's plan names TDD and Playwright, ChatGPT's names "browser end-to-end tests", neither has Gherkin features as executable specs.
- No spec framework: Claude's plan rejects Spec Kit and Kiro and proposes ad-hoc `docs/specs/*.md`; ChatGPT's plan has ADRs only. OpenSpec fills this with less ceremony than either rejected tool.
- No delivery-progress dashboard: both cover runtime observability (SigNoz, Grafana), neither shows a founder which changes are specified, built, verified and archived.
- No day-0 sequence: neither says what to type into Claude Code in an empty folder, or in what order the gates must exist before the first feature.
- No accessibility or mutation testing before "later"; no DORA-style flow metrics beyond a mention.

## Locked decisions and the pinned stack

Every pin below was read from a primary source on 24 or 25 October 2026; the first commit records them in `gradle/libs.versions.toml`, `package.json`, `.mise.toml` and image digests, and Renovate keeps them current from week 2. Rows marked "Claude roadmap" reuse that document's verified pins and sources; the rest were verified today.

| Layer | Pin | Note | Source |
| --- | --- | --- | --- |
| Monorepo | Nx 24.0.1, pnpm 12.15, `@nx/gradle` | task-based affected is default; `@nx/gradle` still labelled experimental; Nx Cloud Hobby for remote cache | [Nx changelog](https://nx.dev/changelog) (Claude roadmap) |
| JVM | Java 25 LTS (Temurin), Gradle 9.8.2 Kotlin DSL | next LTS is 29 in Sep 2027 | [Oracle roadmap](https://www.oracle.com/sa/java/technologies/java-se-support-roadmap.html) (Claude roadmap) |
| Backend | Spring Boot 4.1.2, Spring Modulith 2.1.1, Spring AI 2.0.2 | Framework 7, Security 7.1, Jackson 3, `spring-boot-starter-webmvc`; no WebSocket starter anywhere | [Boot 4.1.2](https://spring.io/blog/2026/10/23/spring-boot-4-1-2-available-now/), [Modulith 2.1](https://spring.io/blog/2026/06/11/spring-modulith-2-1-ga-2-0-7-and-1-4-12-released/) (Claude roadmap) |
| Node | 26 LTS (enters LTS on 28 Oct 2026, before the 3 Nov start); 24.22 only if work begins earlier | one `.mise.toml` change | [nodejs.org](https://nodejs.org/en/about/previous-releases) (Claude roadmap) |
| Front end | TypeScript 7.0.2, React 19.3.1, Next.js 16.4.3 (`cacheComponents`), Vite 8.3.5, Lit 3.3.3 | keep the `@typescript/typescript6` alias for tools that need the JS API | [TS 7.0](https://devblogs.microsoft.com/TypeScript/announcing-TypeScript-7-0/), [Next 16.4](https://nextjs.org/blog/next-16-4) (Claude roadmap) |
| Database | PostgreSQL 18.6, pgvector 0.8.x, Flyway Community | evaluate 19.1 in Q1 2027 | [PostgreSQL versioning](https://www.postgresql.org/support/versioning/) (Claude roadmap) |
| Real-time | Centrifugo `v6.10.x` image pinned by digest (6.10.2 per the 24 Oct read), centrifuge-js 5.8, Helm chart 13.7.1 | artefacts Sigstore-signed with SBOMs since 6.10.0; `latest`/`v6` tags always point at stable | [Centrifugo install](https://centrifugal.dev/docs/getting-started/installation) |
| Hot state | Valkey 9.x (9.0 GA 21 Oct 2025, BSD) | Centrifugo engine + Spring rate limits and presence; Sentinel from UAT | [Valkey 9.0](https://www.phoronix.com/news/Valkey-9.0-Released) |
| Jobs | db-scheduler 16.12, ShedLock 7.10 | no broker; `worker` profile | [db-scheduler](https://github.com/kagkarlsson/db-scheduler/releases) (Claude roadmap) |
| Object storage | Garage (AGPLv3, S3 API only) on laptop and bare metal; Hetzner Object Storage or Cloudflare R2 managed | MinIO is archived; confirm Garage and SeaweedFS versions at setup | [Garage](https://github.com/deuxfleurs-org/garage) (Claude roadmap) |
| Identity | Keycloak (CNCF) | version confirmed at setup | Claude roadmap |
| Local stack | Docker Compose v5.6 with Compose Watch; k3d + Tilt weekly | same images as UAT and prod | [Compose develop spec](https://docs.docker.com/reference/compose-file/develop.md) (Claude roadmap) |
| Specs | OpenSpec `@fission-ai/openspec` 1.13.x (MIT, YC W26, Node 20.19+) | `openspec init --tools claude,codex`; `/opsx:propose`, `/opsx:apply`, `/opsx:archive`; `--json` on `list`, `status`, `validate` | [OpenSpec CLI](https://github.com/Fission-AI/OpenSpec/blob/main/docs/cli.md), [npm](https://www.npmjs.com/package/@fission-ai/openspec) |
| BDD, Java | Cucumber-JVM 8.x (MIT, Java 17+, released 24 Sep 2026) with `cucumber-junit-platform-engine` and `cucumber-spring` | Gherkin features per Modulith module, run inside Testcontainers | [Cucumber-JVM v8](https://cucumber.io/blog/open-source/cucumber-jvm-v8) |
| BDD, browser | playwright-bdd 8.x (MIT) on Playwright | Gherkin features compile to Playwright tests; Cucumber reporters included | [playwright-bdd](https://npmjs.com/package/playwright-bdd) |
| Unit, TypeScript | Vitest (current major), browser mode for Lit components | one runner for widget and dashboard | pin at setup |
| Test reporting | Allure Report 3.9.x (Apache 2.0) | one report across Java and TypeScript, history in one JSONL file, quality gates | [Allure 3 docs](https://allurereport.org/docs/v3/) |
| Code quality | SonarQube Cloud free OSS plan for the public repo (unlimited lines, PR and branch analysis) | self-host Community Build 26.8 (Java 21) only if the repo ever goes private | [SonarQube Cloud OSS plan](https://community.sonarsource.com/t/introducing-the-new-free-oss-plan-in-sonarqube-cloud/176923), [Community Build releases](https://github.com/SonarSource/docker-sonarqube/releases) |
| Coverage and mutation | JaCoCo, PIT (Java); Vitest coverage, Stryker (TS) | mutation on core modules from phase 2 | pin at setup |
| Architecture tests | Spring Modulith `verify()`, ArchUnit | fail the build on a cross-module internal import | Claude roadmap |
| Security | gitleaks, Trivy, Semgrep, Renovate, SBOM per image | official docs not opened this session; verify at setup | Claude roadmap |
| Load | k6 speaking Centrifugo's JSON protocol | nightly 2k, 20–30k before UAT | [k6 large tests](https://grafana.com/docs/k6/latest/testing-guides/running-large-tests/) (Claude roadmap) |
| Observability | Micrometer + OpenTelemetry, OTel Collector, SigNoz self-hosted | runtime, not delivery progress | Claude roadmap |

**Decisions carried over unchanged** from the Claude roadmap: ADR-1 modular monolith in `api`, `worker` and `migrate` profiles; ADR-2 Centrifugo as the only real-time runtime; ADR-3 PostgreSQL as the only database; ADR-4 Valkey; ADR-5 db-scheduler; ADR-6 S3 API only; ADR-7 shared schema + RLS; ADR-8 Lit widget with iframe fallback; ADR-9 Next.js; ADR-10 Nx; ADR-11 MIT + `enterprise/`; ADR-12 k3s from UAT; ADR-13 Keycloak; ADR-14 LiteLLM; ADR-15 AGENTS.md canonical; ADR-16 Centrifugo OSS only.

**New decisions in v2.**

| # | Decision | Alternatives rejected |
| --- | --- | --- |
| ADR-17 | OpenSpec is the spec layer; `openspec/specs/` is the behaviour source of truth; every change is an OpenSpec change folder | Spec Kit (heavier), Kiro (IDE-bound), ad-hoc `docs/specs/*.md` |
| ADR-18 | Gherkin is the executable form of OpenSpec scenarios: Cucumber-JVM 8 at the module and API boundary, playwright-bdd at the journey boundary | Serenity BDD (reporting overlap with Allure), Karate (second DSL), jest-cucumber |
| ADR-19 | One test report (Allure 3) and one quality view (SonarQube Cloud), both feeding a static progress dashboard published to GitHub Pages on every green `main` build | Allure TestOps, Backstage, a Grafana GitHub datasource |
| ADR-20 | `audit` is a core module with the UI, export and retention in `enterprise/` | audit entirely in `enterprise/` |
| ADR-21 | Pre-commit runs `openspec validate --archived` and the lint and type-check hooks; CI runs `openspec validate --all --strict --json` | validation only at archive time |

## Engineering operating system

Work enters as an OpenSpec change and leaves as an archived change whose delta is merged into `openspec/specs/`; between those two points every step is a machine-checked gate. Humans write proposals, edit scenarios and review; agents write feature files, tests and code.

### The house loop, per change

&#91;embedded content: house loop · 6 steps, 1 decision, loop back on gaps\]

A change that does not match its spec loops back to implementation; only a change whose scenarios pass reaches review, merge and archive, and archive is what updates the specs.

1. **Propose.** `/opsx:propose <change>` drafts `proposal.md`, delta specs (`### Requirement:` with `#### Scenario:` in GIVEN/WHEN/THEN), `design.md` and `tasks.md`. The human edits the proposal and the scenarios; this is where product thinking happens, and a change the founder has not read does not proceed.
2. **Features first.** For every `#### Scenario:` in the delta spec the agent writes one Gherkin scenario with the same title, tagged `@spec:<capability>`: Cucumber-JVM for module and API behaviour, playwright-bdd for visitor and agent journeys. They fail. `tasks.md` lists each as a task with its verification command.
3. **Red to green.** Inside each task: unit test (JUnit 5 or Vitest), then code, then the `PostToolUse` hook compiles and lints, then the feature passes. Work happens in a worktree (`claude --worktree <change>` or a Codex thread); two or three per engineer is the ceiling.
4. **Verify.** `/opsx:verify` where the installed workflow offers it, otherwise a fresh-context reviewer subagent checks the diff against the delta spec and reports gaps, not style. `openspec validate <change> --strict` must pass.
5. **Pull request.** The template links the change folder. The other vendor's reviewer comments (Codex review on a Claude-driven change and the reverse). Required checks are the gates in the next section. One human who did not drive the agent approves and merges.
6. **Archive.** `openspec archive <change> --yes` runs in CI on the merge commit, merging the delta into `openspec/specs/` and moving the folder to `changes/archive/`. The dashboard moves the change from "verified" to "archived".

### Specs and features stay traceable

- Every `#### Scenario:` under `openspec/specs/**/spec.md` has at least one Gherkin scenario tagged `@spec:<capability>` with the same title, or carries a `@spec-manual` tag with a one-line reason.
- `tools/spec-coverage` (about 100 lines of TypeScript, written in phase 0) parses both trees with `openspec show --json` and the Gherkin parser and writes `spec-coverage.json`. CI fails an archive when a scenario in the change is uncovered; the dashboard shows the percentage over time.
- From phase 1, fork the workflow schema (`openspec schema fork spec-driven spec-driven-bdd`) and add a `features` artifact that requires `specs` and feeds `tasks`, so feature files are drafted during planning rather than remembered during implementation.
- Agents may not edit `openspec/specs/**` directly: a `PreToolUse` deny rule blocks `Edit` and `Write` on that path, so specs change only through archive.

### What lives where

| Concern | Location | Rule |
| --- | --- | --- |
| Behaviour | `openspec/specs/<capability>/spec.md` | source of truth; changed only by archive |
| Why, scope, how, checklist | `openspec/changes/<change>/{proposal,design,tasks}.md` | one folder per change; `x-issue:` in `.openspec.yaml` links the GitHub issue |
| Architecture decisions | `docs/adr/` | `design.md` links to the ADR it relies on |
| Agent conventions | `AGENTS.md` (≤ 200 lines) imported by `CLAUDE.md`; per-app `AGENTS.md`; `.claude/rules/*.md` with `paths:` | Boot 4 facts, the loop above, what done means |
| Project context for OpenSpec | `openspec/config.yaml` → `context:` | three paragraphs the CLI injects into every agent instruction set |
| Hooks | `.claude/settings.json`, committed | `PreToolUse` deny on secrets, `dist/`, `openspec/specs/**`; `PostToolUse` runs `nx affected -t lint,typecheck` and `./gradlew compileJava`; `Stop` runs Modulith `verify()` and `openspec validate --changes` |
| Pre-commit | lefthook or husky | `openspec validate --archived`, gitleaks, formatters |
| Codex | `.codex/config.toml` | same sandbox roots and MCP servers as Claude |
| MCP | `.mcp.json` | GitHub, Playwright, read-only Postgres against Testcontainers; CI uses `--bare` |

### 15-factor, with the gate that checks it

| Factor (Hoffman) | Practice | Gate |
| --- | --- | --- |
| 1 One codebase, one app | one Nx repo; one Spring image with `api`, `worker`, `migrate` profiles | image built once per SHA |
| 2 API first | `libs/contracts/openapi.yaml` and `channels.yaml` are the source; clients generated | contract check fails on breaking change |
| 3 Dependency management | version catalog, lockfiles, pinned digests | Renovate PRs; Trivy on images |
| 4 Build, release, run | immutable images promoted by digest through demo, UAT, prod via Argo CD | release = tag + Helm values, nothing rebuilt |
| 5 Config, credentials, code | env and ConfigMaps; secrets from External Secrets or SOPS | gitleaks; `PreToolUse` deny on secret files |
| 6 Logs | JSON to stdout with trace ids | log schema test in CI |
| 7 Disposability | graceful shutdown, AOT cache, Centrifugo `shutdown.timeout` 30 s | BDD scenario: restart a pod mid-conversation, nothing lost |
| 8 Backing services | PostgreSQL, Centrifugo, Valkey, S3, Keycloak, LiteLLM by URL | Testcontainers swap them in tests |
| 9 Environment parity | same images in Compose, k3d, k3s | weekly `tilt up` rehearsal |
| 10 Admin processes | Flyway as a Kubernetes Job; one-off tasks as Jobs | migration lint rejects non-additive changes |
| 11 Port binding | 8080, 8000, 3000 behind Traefik | Helm values schema |
| 12 Stateless processes | state in PostgreSQL, Valkey, S3 only | ArchUnit: no static mutable state in modules |
| 13 Concurrency | HPA on API and Centrifugo; virtual threads | k6 soak |
| 14 Telemetry | Micrometer + OTel; `gen_ai.*` metrics | span assertions in integration tests |
| 15 Authentication and authorization | Keycloak OIDC; per-connection and per-channel JWTs; tenant RBAC | tenant-isolation invariant suite |

### AI-native means two things here

In the product, AI is sequenced agent-assist → draft mode → confidence-gated autonomy → MCP server, with Promptfoo golden sets and red-team suites gating every prompt or model change, exactly as the Claude roadmap lays out. In the engineering process, agents write most code against OpenSpec changes, hooks and CI are the only guardrails that count, and the budget is $150–250 per developer per month with `--max-budget-usd` on unattended runs. Both AIs route through the same LiteLLM gateway so spend is one number.

## Quality framework per layer

Every layer has a TDD tool for the inside, a BDD tool for the behaviour contract, a gate that fails the PR, and a number it feeds to the dashboard; all of it exists by the end of phase 0, before the first feature. Gherkin is used where a scenario describes behaviour a product person would recognise; plain unit tests cover everything else.

| Layer | TDD (inside) | BDD (contract) | Gate on every PR | Feeds the dashboard |
| --- | --- | --- | --- | --- |
| Modulith modules | JUnit 5 + AssertJ, Mockito only at module edges | Cucumber-JVM 8 features per module, `cucumber-spring`, Testcontainers (PostgreSQL 18, Valkey, Centrifugo, Garage, Keycloak) | Modulith `verify()`, ArchUnit, JaCoCo ≥ 80% on changed lines | coverage, architecture violations (must be 0), feature pass rate |
| REST API | controller slice tests | the same Cucumber features driven over real HTTP on a random port | springdoc spec diffed against `libs/contracts/openapi.yaml`; breaking change fails | contract status |
| Real-time | unit tests on JWT minting and channel naming | Cucumber features with centrifuge-java as the client: publish → receive, recovery after a Centrifugo restart, resync when `recovered: false`, duplicate dropped by `seq` | the recovery feature is a required check | p95 publish-to-receive latency (nightly k6), `recovered` rate |
| Data | repository tests on Testcontainers | tenant-isolation invariant suite: every repository method and native query as tenant A returns zero rows of tenant B | migrations run from zero and from the previous release tag; non-additive migration without `-- destructive` fails | invariant count and status |
| Jobs | db-scheduler task unit tests | feature: offline visitor message produces exactly one email (Mailpit) | duplicate-publication feature: replaying an event is harmless | job queue age (UAT) |
| Widget (Lit) | Vitest browser mode | playwright-bdd journeys on `widget-demo`: pre-chat form, continuity across pages, reconnect, file upload | `size-limit` ≤ 60 KB gzipped; axe-core zero serious violations | bundle size trend, a11y violations |
| Dashboard (Next.js) | Vitest + Testing Library | playwright-bdd journeys: login, inbox live update, assignment, resolve with CSAT | axe-core; TypeScript 7 type-check; ESLint | journey pass rate |
| Product AI | unit tests on prompt builders and the confidence gate | feature: LLM gateway down, visitor and agent still chat; Promptfoo golden set per capability | Promptfoo faithfulness, citation and injection suites | eval score trend, cost per conversation |
| Infrastructure | `helm lint`, kubeconform, Compose config validation | k3d smoke: `tilt up`, login page reachable | Trivy on images, gitleaks, Semgrep | CVE count by severity |
| Mutation (phase 2+) | PIT on `conversation`, `realtime`, `tenancy`; Stryker on `realtime-client` | — | weekly, not per PR | mutation score |

### Required checks, in the order they run

1. `openspec validate --all --strict --json` and the spec-coverage script (seconds).
2. `nx affected -t lint,typecheck,test` with Nx Cloud cache: unit tests, under 3 minutes.
3. Module and API features on Testcontainers: under 8 minutes with `nx affected`.
4. Contract check, tenant invariants, Modulith and ArchUnit.
5. Widget bundle budget and axe.
6. Image build per app with SBOM, Trivy, gitleaks, Semgrep.
7. Journeys (playwright-bdd) against the Compose stack, under 10 minutes.
8. Allure 3 report generated and uploaded; dashboard data regenerated on `main`.

Total budget: 20 minutes on a PR, because an agent waiting longer than that starts guessing. Nightly: k6 at 2,000 connections, PIT and Stryker, Promptfoo full suite, Renovate.

### Definition of done, per pull request

- The OpenSpec change is linked and `openspec status --change <n> --json` reports planning complete.
- Every scenario in the delta spec has a passing Gherkin scenario or a `@spec-manual` reason.
- Unit tests at the right layer; contract updated; migration additive.
- Telemetry added for any new path; `AGENTS.md` or an ADR updated if a convention changed.
- Agent review and human review complete; evidence (Allure link, screenshot, k6 result) attached.

### SLOs from UAT onward

| SLO | Target | Measured by |
| --- | --- | --- |
| Message delivery latency | p95 < 500 ms, p99 < 1.5 s | k6 end-to-end timer; production spans |
| WebSocket connect success | > 99.5% | Centrifugo metrics |
| Reconnect gap recovery | 100% of gaps closed by resync | invariant feature + client telemetry |
| API availability | 99.9% monthly | ingress metrics |
| Error budget | freeze feature merges at 2× burn for 6 hours | alert rule |

## Progress dashboard from day one

The dashboard is a static page in the repo, rebuilt by GitHub Actions on every green `main` build and nightly, and published to GitHub Pages; it has no backend, every panel reads a JSON file that CI wrote, and the first version is a one-day phase-0 task that Claude Code builds from its own OpenSpec change. GitHub Projects stays the board people drag cards on; the dashboard is the single pane that shows whether the project is actually moving.

| Panel | What it shows | Source JSON produced by | Cadence |
| --- | --- | --- | --- |
| Roadmap strip | the six phases, each gate green, amber or red, with its date | `docs/roadmap.yaml`, maintained by hand at each gate review | per gate |
| Change funnel | OpenSpec changes by state: proposed → planned → in progress → verified → archived, with age | `openspec list --json` + `openspec status --change <n> --json` for each change; archive folder count | every `main` build |
| Behaviour surface | requirements and scenarios in `openspec/specs/` over time | `openspec show <spec> --json` summed per capability | every `main` build |
| Spec coverage | % of spec scenarios with a passing Gherkin scenario, uncovered list | `tools/spec-coverage` | every `main` build |
| Tests | passed, failed, flaky by layer (unit, module, API, real-time, journey, AI), link to the Allure 3 report | Allure 3 summary + its history JSONL | every `main` build |
| Coverage and mutation | JaCoCo and Vitest line coverage; PIT and Stryker scores | report XML and JSON | per build; weekly for mutation |
| Quality gate | SonarQube Cloud gate status, new-code issues, duplication | SonarQube Cloud web API | per build |
| Widget budget | gzipped bundle size against the 60 KB line | `size-limit --json` | per build |
| Security | Trivy findings by severity, open Renovate PRs, gitleaks status | SARIF and GitHub API | per build |
| Flow | lead time for changes (PR opened → merged), merges per week, reverts and hotfix count | GitHub API in the build script | nightly |
| Agent spend | Claude Code and Codex cost per week, cost per archived change | LiteLLM usage export | nightly |
| Runtime (from UAT) | nightly k6 p95 latency, connect success, `recovered` rate; link to SigNoz | k6 summary JSON | nightly |
| Product AI (from phase 2) | Promptfoo scores per capability, inference cost per conversation | Promptfoo JSON, LiteLLM | nightly |

### How it is built

- `apps/progress/`: one HTML page with a small Lit component per panel (the same library as the widget, so nothing new to learn) and the design-system tokens; charts drawn as SVG from the JSON.
- `tools/progress-data/`: a Node script that runs the OpenSpec, Allure, coverage and GitHub API queries and writes `data/*.json` plus a dated snapshot into `data/history/`, which is what makes trends possible without a database.
- `.github/workflows/progress.yml`: runs after the CI workflow on `main` and on a nightly schedule, commits the snapshot to the `gh-pages` branch and deploys with `actions/deploy-pages`.
- Day 1 shows one archived change (`bootstrap-workspace`) and every gate panel at its starting value; that is the point, the page exists before there is anything to show.

### What it deliberately is not

- Not Backstage or a Grafana GitHub datasource: both need a server a four-person team would have to run.
- Not Allure TestOps or a paid SaaS: Allure 3 history in a JSONL file covers the trend.
- Not a replacement for SigNoz: runtime health lives there; the dashboard links to it from UAT onward.

## Day-0 bootstrap: empty folder to green CI with Claude Code

Week 1 produces five archived OpenSpec changes and a live dashboard, and nothing product-shaped; the gates exist before the first feature because agents will write most of the code and a gate is the only instruction they cannot ignore. One engineer drives Claude Code; the others install the same laptop setup and review.

### Laptop prerequisites (one afternoon)

- 32 GB RAM is the floor, 64 GB avoids swapping during local load tests; Windows users work inside WSL2 with the repo on the Linux filesystem.
- `git`, GitHub CLI (`gh auth login`), Docker Desktop or Docker Engine with Compose v5.6.
- `mise` to pin toolchains per repo: `mise use java@temurin-25 node@26 pnpm@12` writes `.mise.toml`.
- Claude Code (install per [code.claude.com](https://code.claude.com/docs/en/overview)), Codex CLI for the second reviewer, `npm i -g @fission-ai/openspec@latest`.
- `k3d`, `kubectl`, `helm`, `tilt` for the weekly Kubernetes rehearsal (can wait until day 5).
- Playwright browsers (`pnpm dlx playwright install`) after the workspace exists.

### Day 1: repo, agent files, workspace

1. `mkdir livechat && cd livechat && git init && gh repo create <org>/livechat --public --source=.` The repo is public from the first commit: MIT core, free SonarQube Cloud, free GitHub Pages.
2. `openspec init --tools claude,codex` creates `openspec/`, `.claude/skills/openspec-*`, `.claude/commands/opsx/` and the Codex equivalents.
3. Start `claude` and give it the first prompt (below). It writes `AGENTS.md`, `CLAUDE.md` (`@AGENTS.md` plus Claude-only lines), `openspec/config.yaml` with a `context:` block, and `.claude/settings.json` with the deny rules. Commit before anything else exists.
4. `/opsx:propose bootstrap-workspace`. Read `proposal.md`, cut scope until the scenarios fit on one screen, then `/opsx:apply`. This change is also the Claude roadmap's week-1 spike: if Claude Code cannot drive the Gradle multi-project inside Nx, you find out on day 1.
5. `openspec archive bootstrap-workspace --yes`, push.

The first prompt, roughly:

```markdown
Write AGENTS.md (under 200 lines) for this repo. Stack facts to state as rules:
Spring Boot 4.1.2 (Framework 7, Jackson 3 `tools.jackson`, `spring-boot-starter-webmvc`,
Jakarta EE 11), Spring Modulith 2.1.1, Java 25, Gradle 9.8 Kotlin DSL with a version
catalog; Nx 24 + pnpm 12 + TypeScript 7; Next.js 16.4 with cacheComponents; Lit 3.3.
No WebSocket or STOMP code in Spring: Centrifugo owns every socket.
Every change starts with /opsx:propose; every spec scenario gets a Gherkin scenario
(Cucumber-JVM 8 or playwright-bdd) tagged @spec:<capability> before code is written.
Tests first. Migrations additive. Secrets from env only.
Then write CLAUDE.md as `@AGENTS.md` plus a line that the Stop hook runs Modulith
verify, and openspec/config.yaml with a three-paragraph context: product, stack,
conventions. Do not create any application code yet.
```

Scope of `bootstrap-workspace`: Nx 24 workspace with pnpm, Gradle root with `libs.versions.toml`, `apps/api` as a Boot 4.1.2 Modulith with the 12 modules as empty packages and the `ApplicationModules.verify()` test, `apps/dashboard` Next.js shell, `apps/widget` Lit shell with `loader.js`, `apps/widget-demo`, `apps/progress` placeholder, `libs/contracts/openapi.yaml`, `infra/docker/compose.yaml` seeded from the ChatGPT roadmap's file with Garage, Keycloak, Mailpit and an OTel collector added, `.devcontainer`. Scenarios: a fresh clone passes `pnpm install && ./gradlew build && nx run-many -t build`; `docker compose up` reports every service healthy; `nx graph` shows `api` as a Gradle project.

### Day 2: `add-quality-gates`

Cucumber-JVM 8 with `cucumber-junit-platform-engine`, `cucumber-spring` and Testcontainers, proven by one walking-skeleton feature (`GET /actuator/health` returns UP against real PostgreSQL and Valkey); Vitest for widget and dashboard; playwright-bdd with one journey (the widget demo page shows the launcher); ArchUnit rules (no WebSocket classes, every entity has `tenant_id`, no JDBC in controllers); JaCoCo, Spotless, ESLint, Prettier, `size-limit`; Allure 3 collecting both sides; `tools/spec-coverage`; lefthook pre-commit with `openspec validate --archived`, gitleaks and formatters; `PostToolUse` and `Stop` hooks in `.claude/settings.json`.

### Day 3: `add-ci-pipeline`

`ci.yml` running the eight required checks with `nx affected` and Nx Cloud Hobby; `images.yml` building one image per app on `main` with SBOM and Trivy; branch protection via `gh api` (required checks, one human review, no admin bypass); SonarQube Cloud connected on the free OSS plan; Renovate config; the second-vendor reviewer (Codex review on PRs, Claude Code review as the GitHub Action) installed as comment-only.

### Day 4: `add-progress-dashboard`

`apps/progress`, `tools/progress-data`, `progress.yml` deploying to GitHub Pages, `docs/roadmap.yaml` with the six phases and gates. The change's own scenarios are the dashboard's acceptance tests: "after a `main` build the page lists every change with its state", "a gate marked red in `roadmap.yaml` renders red".

### Day 5: `add-k8s-rehearsal`

Helm charts for `api`, `worker` and `dashboard`, an umbrella chart pulling the official Centrifugo chart 13.7.1, `values-{laptop,demo,uat,prod}.yaml`, k3d plus Tilt; `helm lint` and kubeconform in CI. Exit for week 1: `tilt up` on k3d serves the Keycloak login page, CI is green, the dashboard shows five archived changes.

### Weeks 2–4 of phase 0

- Keycloak OIDC login in the dashboard; Flyway with the migration lint and the (still empty) tenant-isolation harness; the `realtime` module's connection JWT and `connect` proxy with the recovery feature scaffolded and skipped; SigNoz behind the `observability` Compose profile.
- Fork the OpenSpec schema to `spec-driven-bdd` with the `features` artifact once the pattern has held for a few changes.
- Each time an agent makes the same mistake twice, one line goes into `AGENTS.md` through a PR; `/doctor prompt-audit` runs at the end of the phase.
- Optional in phase 0, required by phase 2: the release path to a €6 VPS with Argo CD, so the demo rung is a Helm values file away.

## Phased roadmap with exit gates

Six phases take an empty folder to HA production and the open-source launch in 56 weeks from a start on Monday 3 November 2026; the buffer sits in phases 0–2 (22 weeks against the Claude plan's 18) because that is where the spec and BDD discipline is learned and where Spring Boot 4 fluency is unproven. Every gate is demonstrated live from the dashboard and the running system, never signed off on paper.

&#91;embedded content: phase dates typed from this plan · 3 Nov 2026 to 26 Nov 2027\]

Phase 0 is the one to start now; each later band opens only when the diamond before it is green on the dashboard.

| Phase | Weeks and dates | Delivers | Exit gate, demonstrated live |
| --- | --- | --- | --- |
| 0 Foundation | 1–4, 3 Nov – 27 Nov 2026 | week-1 bootstrap above; Keycloak login; Flyway and migration lint; tenant-invariant harness; `realtime` skeleton; SigNoz profile; Helm on k3d | fresh clone to green CI in under 20 min; dashboard live; `tilt up` serves the login page; Gradle-in-Nx spike passed; walking-skeleton features green on Testcontainers |
| 1 Core chat | 5–12, 30 Nov 2026 – 22 Jan 2027 (two holiday weeks inside) | conversations and messages with per-conversation `seq`; Centrifugo JWTs, `connect` and `rpc` proxies, history and recovery; db-scheduler; widget with loader, pre-chat form, continuity token, resync, presigned upload; identity verification; dashboard inbox with live updates | a visitor on the demo site and an agent in the dashboard chat end to end; the "pull the network cable" and "restart Centrifugo mid-conversation" features pass; tenant-invariant suite is non-empty and green; nightly k6 at 2,000 connections |
| 2 Team inbox and demo | 13–22, 25 Jan – 2 Apr 2027 | teams, inboxes, assignment with capacity, canned replies, labels, notes, status workflow, business hours, CSAT, transcripts, offline-to-email, OpenAPI, webhooks, Slack, basic reports; agent-assist through LiteLLM; `audit` table; PIT and Stryker weekly; demo on a €6 VPS behind Cloudflare via Argo CD | 10–20 demo users for a week without incident; first design partner on a real site; agent-assist used daily; spec coverage above 90% |
| 3 Knowledge, AI draft, 10k UAT | 23–32, 5 Apr – 11 Jun 2027 | help center with hybrid search; ingestion pipeline and `knowledge_chunk`; AI agent in human-approved draft mode; Promptfoo golden sets and red-team suite in CI; k3s on Hetzner AX42-1 with CloudNativePG, Centrifugo chart, Valkey + Sentinel, Garage, Traefik, Argo CD, SigNoz | 10,000-connection soak for 30 minutes at p95 < 500 ms with a Valkey Sentinel failover mid-run; `recovered` rate reported; AI drafts approved at a measured acceptance rate |
| 4 Autonomy, channels, enterprise | 33–44, 14 Jun – 3 Sep 2027 | confidence-gated autonomous agent with the resolution definition and non-billable escalations; customer tools and the platform MCP server; email channel; `enterprise/` SLA, custom roles, audit UI, SSO; usage metering; Stripe | the autonomous agent handles live traffic for two tenants with reopen rate on the dashboard; the enterprise tier can be quoted |
| 5 Production scale and launch | 45–56, 6 Sep – 26 Nov 2027 | three-node HA cluster; CloudNativePG failover rehearsed; backup restored in a drill; Centrifugo behind HPA and a PodDisruptionBudget; Argo CD ApplicationSet for a second region; EU/US choice at signup; SOC 2 readiness; public launch with MIT core and MIT widget | SLOs met for a calendar month; RPO and RTO numbers tested, not asserted; first paying customers on production |

### How the phases run

- Each phase is a GitHub milestone; each change inside it is an OpenSpec change linked to an issue through `x-issue:`; the dashboard funnel is per milestone, so the founder sees how many changes remain before a gate.
- Phase 0 is driven by one engineer with Claude Code; from phase 1 all three or four run the house loop in parallel worktrees, with the backend owner holding the `realtime`, `conversation` and `tenancy` modules and every migration.
- A gate that fails stays red on the dashboard and the phase does not close; the next phase's changes may be proposed but not applied.
- The week numbers move; the gates do not. A missed gate is reported with the reason and the new date in `docs/roadmap.yaml`, which is what the dashboard draws.

## Environment ladder

The same container images and Helm releases run at every rung and only the host changes; the ladder below keeps the Claude roadmap's hosting findings (verified 24 Oct 2026) and adds which gate each rung serves. "Free and always-on with WebSockets" has almost disappeared in 2026, so the demo rung is a €6 VPS unless the team accepts reconnects on Oracle's free ARM tier.

| Rung | Serves | Compute | PostgreSQL | Centrifugo, Valkey, object storage | Cost (Oct 2026) |
| --- | --- | --- | --- | --- | --- |
| 1 Laptop | every PR; gates 0 and 1 | Docker Compose with Compose Watch; k3d + Tilt weekly | Postgres container; Testcontainers in tests | one Centrifugo (memory engine locally, Valkey engine in the e2e profile), Valkey, Garage | $0 |
| 2 Demo | gate 2, 10–20 users, design partner | Hetzner CX23/CAX11 (≈€6) or Oracle Always Free A1 (2 OCPU/12 GB, reconnects on idle), Compose, Cloudflare Free in front | same box, or Neon Free (suspends after 5 min idle) | Centrifugo with Valkey engine on the box; attachments to Garage or Cloudflare R2 | €0–9/mo |
| 3 UAT at 10k | gate 3 | one Hetzner AX42-1 dedicated (8c/16t, 64 GB, 2×512 GB NVMe, ≈€97/mo) running k3s; AX102-1 (≈€257) if trace retention is heavy; OVH Advance as the alternative | CloudNativePG, 1 primary + 1 replica, WAL to the S3 bucket | two Centrifugo pods (1 vCPU/2 GB) from chart 13.7.1; Valkey primary + replica with Sentinel; Garage | ≈€100–260/mo |
| 4 Production | gates 4 and 5 | three-node k3s or Talos on bare metal or any managed Kubernetes; HPA on API and Centrifugo | CloudNativePG with automated failover, PgBouncer, backups to object storage | Centrifugo 3+ pods with a PodDisruptionBudget; Valkey + 3 Sentinels; Garage 3-node or managed S3 | from ≈€300–800/mo, linear |

### Rules that hold at every rung

- Every hop has an idle timer (Cloudflare 100 s, ALB 60 s default, nginx 60 s); Centrifugo's 25 s ping clears them, and proxy read and send timeouts are still set ≥ 3600 s with `Upgrade` and `Connection` passed through and `/emulation` routed to the same backend set.
- Zero-downtime deploy: Centrifugo `shutdown.timeout` 30 s sends code 3001 and centrifuge-js reconnects elsewhere with recovery; Spring `server.shutdown=graceful` with a `preStop` sleep; no sticky sessions anywhere.
- Not used: Vercel Hobby (commercial use forbidden), Cloud Run for the real-time tier (60-minute WebSocket cap), Cloudflare Workers (no Java), MinIO (archived Feb 2026), Bitnami charts (paid since Sep 2025).
- Hybrid and bare metal: every cluster runs the complete stack and tenants are pinned to one cluster at signup; one Argo CD ApplicationSet with the cluster generator adds a region by adding a cluster secret; CloudNativePG replica clusters give DR, never multi-master; one Garage per cluster, never stretched across providers.

## Risks, open questions and sources

The new risks in v2 are process risks: the spec and BDD discipline can slow agents down if the step-definition library is not kept small, and three of the new tools are young. The architectural risks are the Claude roadmap's, unchanged.

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Gherkin step bloat: hundreds of near-duplicate steps make features slower to write than code | medium | one shared step library per layer owned by a human; steps are domain verbs, not UI clicks; a change that adds more than five new steps needs a reviewer comment |
| OpenSpec is young (1.x, slash commands renamed within a year) | medium | the artefacts are plain Markdown in git; a CLI change costs an `openspec update`; the spec format is stable enough to parse with 100 lines of our own code |
| Cucumber-JVM 8 (Sep 2026) and Allure 3 are recent majors | low | both keep the v7 / v2 wire formats; pin, let Renovate move patch versions |
| Agents write Boot 3 and Jackson 2 idioms, or add WebSocket code | high | `AGENTS.md` states the Boot 4 facts; ArchUnit forbids WebSocket and STOMP classes; the compile hook catches the rest; week-1 spike |
| Dashboard rots into a vanity page | medium | every panel is a gate input or an SLO; a panel nobody acts on for a month is removed in a PR |
| Centrifugo single-vendor project | medium | Apache 2.0 single binary, MIT SDKs and chart, images mirrored; the Spring WebSocket gateway stays in ADR history as the exit |
| Valkey untested by Centrifugal Labs as an engine | medium | failover and load tests in phase 1 and in the UAT soak; Redis 8 or KeyDB behind the same address as fallback |
| Multi-tenant data leak | low, catastrophic | RLS with `FORCE`, Hibernate `@TenantId`, the invariant suite, tenant id in every channel name and JWT, human review on every query an agent writes |
| Free demo tier drops sockets | high on free PaaS | €6 VPS; reconnect with recovery makes a drop invisible within seconds |
| Four people cannot run Kubernetes, observability and LLM ops | medium | k3s single node until UAT, SigNoz all-in-one, managed Postgres and S3 as escape hatches, no microservices before phase 5 |
| Prompt injection through visitor text or scraped knowledge | high | retrieved text is data, structured output, tool allow-lists, Promptfoo red-team suite in CI |
| LLM cost blow-up on one tenant | medium | per-tenant budgets and keys in LiteLLM; hard caps route to humans |

### Open questions for the founders

- [ ] Who is the one person who reads every `proposal.md` before `/opsx:apply`? The loop needs a named product owner.
- [ ] Is the repository public from the first commit (MIT, free SonarQube Cloud and Pages) or private until launch (self-hosted SonarQube Community Build, paid Pages)?
- [ ] Which region do the first customers sit in, EU or US? It decides the first cluster and the inference data-residency surcharge.
- [ ] Does the first paying customer need email as a channel? It pulls phase 4 work into phase 3.
- [ ] Self-hosted licences early (`enterprise/` from phase 2) or SaaS only until phase 5?
- [ ] First model provider for evals: Claude Haiku-class or Gemini Flash-Lite?
- [ ] Claude Team or Enterprise plan for managed code review, or the open-source GitHub Action plus Codex review?

### Sources

Opened on 25 Oct 2026 for this document:

- [OpenSpec CLI reference](https://github.com/Fission-AI/OpenSpec/blob/main/docs/cli.md) · [OpenSpec concepts and spec format](https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md) · [@fission-ai/openspec on npm](https://www.npmjs.com/package/@fission-ai/openspec) · [OpenSpec repository](https://github.com/Fission-AI/openspec)
- [Cucumber-JVM v8 announcement](https://cucumber.io/blog/open-source/cucumber-jvm-v8) · [playwright-bdd on npm](https://npmjs.com/package/playwright-bdd) · [Allure Report 3 docs](https://allurereport.org/docs/v3/) · [Allure 3 migration](https://allurereport.org/docs/v3/migrate/)
- [SonarQube Cloud free OSS plan](https://community.sonarsource.com/t/introducing-the-new-free-oss-plan-in-sonarqube-cloud/176923) · [SonarQube Community Build releases](https://github.com/SonarSource/docker-sonarqube/releases) · [SonarQube Cloud plans](https://www.sonarsource.com/products/sonarqube/cloud/new-pricing-plans/)
- [Centrifugo installation and signing](https://centrifugal.dev/docs/getting-started/installation) · [Valkey 9.0 release](https://www.phoronix.com/news/Valkey-9.0-Released)

Reused from the Claude roadmap (read 24 Oct 2026), where each pin's primary source is linked:

- Nx, Java, Spring Boot 4.1.2, Modulith, Spring AI, Gradle, Node, pnpm, TypeScript, React, Next.js, Vite, Lit, PostgreSQL, pgvector, Docker Compose, db-scheduler, ShedLock, Garage, Hetzner and Oracle pricing, Vercel fair use, Cloud Run WebSocket limits, Centrifugo capacity and channel design, k6, Claude Code and Codex workflow docs, Thoughtworks Radar Vol. 34, AI vendor pricing.

Known gaps: Semgrep, Trivy, Renovate, gitleaks, lefthook, kubeconform and Keycloak versions were not opened today and are pinned at setup; Allure 3's "coming soon" CI integrations are not relied on (the report is a static site either way); Claude Code's review GitHub Action name and Codex review terms are re-verified on day 3.
