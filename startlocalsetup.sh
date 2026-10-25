#!/usr/bin/env bash
# Start the local setup (bash / Git Bash / WSL / macOS / Linux).
#
#   ./startlocalsetup.sh            full container stack: infra + Flyway + backend + dashboard + widget (Mode B)
#   ./startlocalsetup.sh --infra    infrastructure only (Mode A); then run the apps with pnpm dev:* for hot reload
#
# Safe to rerun: creates .env only if missing, otherwise only appends keys newly added to .env.example
# (existing values are never changed). Never deletes volumes or data. Stop with ./stoplocalsetup.sh.
set -euo pipefail
cd "$(dirname "$0")"

mode=containers
case "${1:-}" in
  "") ;;
  --infra) mode=infra ;;
  -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
  *) echo "Unknown option: $1 (use --infra or --help)" >&2; exit 2 ;;
esac

docker info >/dev/null 2>&1 || { echo "Docker is not reachable. Start Docker Desktop and retry." >&2; exit 1; }

secret() { openssl rand -hex 24 2>/dev/null || od -An -tx1 -N24 /dev/urandom | tr -d ' \n'; }

# .env: create from .env.example, or append keys missing from an existing file. Values are never printed.
[ -f .env ] || : > .env
added=()
while IFS= read -r line; do
  [[ "$line" =~ ^([A-Z0-9_]+)=(.*)$ ]] || continue
  key="${BASH_REMATCH[1]}"
  grep -q "^${key}=" .env && continue
  if [ "${BASH_REMATCH[2]}" = "__generate__" ]; then
    printf '%s=%s\n' "$key" "$(secret)" >> .env
  else
    printf '%s\n' "$line" >> .env
  fi
  added+=("$key")
done < .env.example
[ ${#added[@]} -eq 0 ] || echo "OK   .env: added ${added[*]} (values not printed)"

if [ "$mode" = infra ]; then
  files=(-f docker-compose.yml)
else
  files=(-f docker-compose.yml -f docker-compose.apps.yml)
fi

echo ">>> docker compose ${files[*]} config"
docker compose "${files[@]}" config -q
echo ">>> docker compose ${files[*]} up --build -d --wait"
docker compose "${files[@]}" up --build -d --wait
docker compose "${files[@]}" ps --format 'table {{.Service}}\t{{.Status}}'

cat <<EOF

Local setup is up ($mode mode). LOCAL POC — NOT REAL AUTHENTICATION.
  Grafana      http://localhost:3001   (admin password: GRAFANA_ADMIN_PASSWORD in .env)
  Prometheus   http://localhost:9090
  Centrifugo   http://localhost:8000/health
EOF
if [ "$mode" = infra ]; then
  cat <<EOF

Start the apps with hot reload (one terminal each):
  pnpm dev:backend     # http://localhost:8080
  pnpm dev:dashboard   # http://localhost:3000/agent  (agentbob)
  pnpm dev:widget      # http://localhost:5173/demo   (userone)
EOF
else
  cat <<EOF
  Visitor      http://localhost:5173/demo   (userone)
  Agent        http://localhost:3000/agent  (agentbob)
  Backend      http://localhost:8080/actuator/health
EOF
fi
echo "Stop with: ./stoplocalsetup.sh"
