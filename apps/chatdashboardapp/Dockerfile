# syntax=docker/dockerfile:1
# Build context: repository root (shared libs + lockfile). Runs the Next.js standalone production server.
FROM node:24.22.1-alpine3.24 AS build
ENV NX_DAEMON=false NX_NO_CLOUD=true NEXT_TELEMETRY_DISABLED=1 CI=true NEXT_OUTPUT=standalone
RUN corepack enable
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY tsconfig.base.json ./
COPY libs/ libs/
COPY apps/chatdashboardapp/ apps/chatdashboardapp/
RUN cd apps/chatdashboardapp && ../../node_modules/.bin/next build

FROM node:24.22.1-alpine3.24
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
WORKDIR /app
# Standalone output mirrors the repo layout (tracing root = repo root).
COPY --from=build --chown=node:node /repo/apps/chatdashboardapp/.next/standalone ./
COPY --from=build --chown=node:node /repo/apps/chatdashboardapp/.next/static ./apps/chatdashboardapp/.next/static
COPY --from=build --chown=node:node /repo/apps/chatdashboardapp/public ./apps/chatdashboardapp/public
USER node
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:3000/ >/dev/null || exit 1
CMD ["node", "apps/chatdashboardapp/server.js"]
