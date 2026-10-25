# syntax=docker/dockerfile:1
# Build context: repository root (shared libs + lockfile). Static bundle served by unprivileged nginx on :8080.
FROM node:24.22.1-alpine3.24 AS build
ENV NX_DAEMON=false NX_NO_CLOUD=true CI=true
RUN corepack enable
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pnpm,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY tsconfig.base.json ./
COPY libs/ libs/
COPY apps/chatwidget/ apps/chatwidget/
RUN cd apps/chatwidget && ../../node_modules/.bin/vite build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine3.24
COPY apps/chatwidget/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/dist/apps/chatwidget /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/ >/dev/null || exit 1
