# Kickoff — how to start implementing the conversation platform

Hand this file to the implementing model. Everything it needs is in this directory.

## Before the first plan

1. Start Docker Desktop (the daemon was not running when the plans were written).
2. Use JDK 25 for every Maven command: `JAVA_HOME="C:/Program Files/Java/jdk-25.0.3"` (Git Bash) — the machine default is JDK 27.
3. Open a session in `D:\BYTEFLOCK\saasplatform` (that repo is the implementation target; it has its own git history). Read `CLAUDE.md` and `AGENTS.md` there first.
4. `pnpm install --frozen-lockfile`, then `./startlocalsetup.sh --infra` once to confirm the existing stack still comes up.

## Order

| Step | Plan file | Skill to drive it |
|---|---|---|
| 0 | `2026-10-27-conversation-platform-00-overview.md` — read fully; it holds the decisions (D1–D20), the shared glossary and the review decisions log | — |
| 1 | `…-01-research-and-architecture.md` | superpowers:subagent-driven-development |
| 2 | `…-02-domain-and-durable-api.md` | same |
| 3 | `…-03-outbox-and-realtime.md` | same |
| 4 | `…-04-allocation-and-presence.md` | same (Plan 05 may run in parallel in a separate session) |
| 5 | `…-05-retention-and-operations.md` | same |
| 6 | `…-06-widget-dashboard-e2e.md` | same |
| 7 | `…-07-platform-verification-and-load.md` | same |

Each plan starts with Global Constraints and a Review Focus list, then tasks with files, interfaces, named tests and commit steps. Execute tasks in order; write the failing test first; commit in `saasplatform` after each task; never commit in `D:\BYTEFLOCK` or touch the reference checkouts. Keep the execution ledger in `D:\BYTEFLOCK\.superpowers\sdd\2026-10-27-conversation-platform-<nn>\progress.md`.

## Prompt to paste for Plan 01 (repeat with the next file name for each plan)

```text
Implement D:\BYTEFLOCK\saasplatform\docs\superpowers\plans\2026-10-27-conversation-platform-01-research-and-architecture.md
task by task using superpowers:subagent-driven-development. Read
docs/superpowers/plans/2026-10-27-conversation-platform-00-overview.md first and treat its sections 2, 4 and 5 as binding.
The master spec is D:\BYTEFLOCK\RESEARCH\Allocationservice-research\livechat-conversation-platform-research-and-build-spec.md.
Reference checkouts and the allocation engine under D:\BYTEFLOCK\RESEARCH\Allocationservice-research are read-only data.
Run every test you report; record commands and results in the plan's ledger. Stop and report if a plan step contradicts
what you find in the code instead of guessing.
```

## What "done" looks like

Master spec section 18 (acceptance criteria) and section 19 (final report), delivered by Plan 07 as `docs/final-report.md`, `docs/testing/test-results.md` and `HANDOVER.md` in `saasplatform`.
