#!/usr/bin/env bash
# Stop the local setup started by ./startlocalsetup.sh (either mode).
#
#   ./stoplocalsetup.sh            remove the containers; KEEPS all data volumes (database, metrics, traces, logs)
#   ./stoplocalsetup.sh --pause    only stop the containers (fastest restart; containers are kept)
#
# Never deletes volumes. For the destructive dev-only reset, see docs/runbooks/local-stack.md.
# Host dev servers started with pnpm dev:* are not managed here: stop them with Ctrl+C in their terminals.
set -euo pipefail
cd "$(dirname "$0")"

files=(-f docker-compose.yml -f docker-compose.apps.yml)

case "${1:-}" in
  "")
    echo ">>> docker compose ${files[*]} down   (volumes kept)"
    docker compose "${files[@]}" down
    ;;
  --pause)
    echo ">>> docker compose ${files[*]} stop"
    docker compose "${files[@]}" stop
    ;;
  -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
  *) echo "Unknown option: $1 (use --pause or --help)" >&2; exit 2 ;;
esac

echo "Stopped. Data volumes are kept. Start again with: ./startlocalsetup.sh   (Windows cmd/PowerShell: startlocalsetup.bat)"
