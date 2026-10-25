# infra

Configuration mounted read-only into the local Compose services (`docker-compose.yml`).

| Path | Service |
|---|---|
| `centrifugo/config.json` | Centrifugo v6: port 8000, explicit `allowed_origins`, namespace `chat` (client subscribe/publish denied), subscription tokens enabled, admin disabled, `/health`. Secrets (`CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_HTTP_API_KEY`) come from `.env`. |
| `observability/otel-collector.yaml` | OTLP receivers (4317/4318) → traces to Tempo (`otlp_grpc`), logs to Loki (`otlp_http` → `/otlp`); own metrics on :8888; health on :13133 |
| `observability/prometheus.yml` | Mode A: scrapes Prometheus, Collector, backend on the host |
| `observability/prometheus.containers.yml` | Mode B (mounted by `docker-compose.apps.yml`): same, backend container target only |
| `observability/tempo.yaml` | Tempo 3.1 single binary, local storage |
| `observability/loki.yaml` | Loki 3.7 single binary, filesystem, tsdb v13 + structured metadata (needed for OTLP) |
| `observability/grafana/provisioning` | datasources (Prometheus, Tempo↔Loki trace/log links) + dashboard provider |
| `observability/grafana/dashboards/platform-overview.json` | *Chat Platform - Local Overview* dashboard |

No Docker socket is mounted; logs arrive via OTLP from the application (Logback OpenTelemetry appender), not by
scraping container stdout. Container stdout stays available through `docker compose logs`.

**Not for UAT/production as-is:** Grafana, Prometheus, Loki, Tempo, the Collector and actuator endpoints have no
authentication/TLS here; they are bound to `127.0.0.1` only. Production needs auth, TLS, network policies,
retention settings and object storage for Loki/Tempo.
