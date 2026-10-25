# ADR-0001: One repository orchestrated by Nx

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The platform has several frontends (dashboard, embeddable widget), shared design tokens and component libraries,
a Java backend, migrations, contracts and tests. Changes often span several of them (e.g. API + UI + migration).

## Decision

Use a single Git repository with an Nx 24 integrated workspace (pnpm, TypeScript path aliases, `project.json` per
project). Nx orchestrates tasks and caching; each tool keeps its own build (Next.js, Vite, Maven Wrapper, Flyway,
Docker Compose). Project tags + `@nx/enforce-module-boundaries` encode allowed dependencies.

## Alternatives

- Polyrepo per app/service: independent versioning, but cross-cutting changes need coordinated PRs and duplicated CI.
- pnpm workspaces without Nx: simpler, but no affected-graph CI, task caching or boundary linting.
- `@nx/maven` plugin for the backend: not adopted yet; plain `nx:run-commands` calling the wrapper is enough for one
  Maven project.

## Consequences

- Atomic changes across contracts, backend and UI; one CI pipeline with `nx affected`.
- Contributors must learn Nx basics; Nx version upgrades go through `nx migrate`.
- Validated in this setup: `nx show projects` lists all 9 projects; boundary violations were shown to fail lint.
