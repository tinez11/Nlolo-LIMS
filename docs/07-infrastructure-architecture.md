# Deliverable 7 — Infrastructure Architecture
**Digital Life Insurance Core Platform — Tanzania (Phase 0, Artifact 7 of 8)**

> Companion files: `infra/docker-compose.yml` + `infra/docker-compose.observability.yml` (validated with `docker compose config` — 15 services merge cleanly), `infra/postgres/Dockerfile`, `infra/postgres/init/01-create-app-role.sql`, `infra/app/Dockerfile`, `db-migrations/_post-migration/configure-pg-partman.sql`, `observability/prometheus.yml`, `observability/alert-rules.yml`, `observability/tempo.yaml`, `.github/workflows/ci-cd.yml`.

---

## 1. One Decision Flagged Up Front: Camunda 7 (Embedded), Not Camunda 8/Zeebe

Your stack listed "Camunda" without specifying which generation. I've built this around **Camunda 7's embedded engine** — it runs inside the `app` process itself (a Spring Boot starter, not a separate service), persisting to its own schema in the same Postgres instance. This is why there's no `camunda` container in `docker-compose.yml`. The alternative, Camunda 8/Zeebe, is a separately-deployed distributed runtime reached over gRPC — architecturally that would mean the surrender/maturity choreography (Deliverable 3 Rev 2 §12) lives *outside* the `policy` module in a different distributed system, which cuts against the modular-monolith rationale your whole stack is built on. Embedded Camunda 7 keeps that process definition as part of `policy`'s own application layer, exactly as designed. **Flag if this reading is wrong** — if you specifically meant Camunda 8, this decision (and the compose file) needs to change before Deliverable 8's build order references it.

---

## 2. Docker Compose (Local Development)

`docker-compose.yml` — the core stack every developer runs: `postgres` (custom image, described below), `redis` (caching + USSD session state per your stated infrastructure reality), `minio` (+ an init job pre-creating the `policy-documents`/`kyc-evidence` buckets), `keycloak` (with realm auto-import for the four realms from Deliverable 4), `app` (the monolith itself), `mock-mobile-money` (a WireMock instance simulating M-Pesa/Airtel Money/Tigo Pesa callbacks — so `payment`'s ACL is exercisable without live aggregator sandbox credentials), and `mailpit` (local email capture for `communication`'s EMAIL channel testing). `pgadmin` is behind a `tools` profile so it doesn't start by default.

`docker-compose.observability.yml` — an optional overlay (`docker compose -f docker-compose.yml -f docker-compose.observability.yml up`) adding Prometheus, Alertmanager, Grafana, Loki, Promtail, Tempo, and a `postgres-exporter`. Kept separate from the core file so a developer who just wants the app running isn't forced to also run six extra containers.

**`postgres`'s Dockerfile** extends `postgres:16` with `pg_partman` and `pg_cron` installed at build time — this resolves Deliverable 6's open item on partition-maintenance mechanism (§3 below). **`postgres/init/01-create-app-role.sql`** creates `app_role` with `NOSUPERUSER NOBYPASSRLS` explicitly — the exact attributes Deliverable 6's validation showed are load-bearing for Row-Level Security to mean anything at all — plus a separate `keycloak` database so a Keycloak upgrade never touches business schemas.

---

## 3. Resolving Deliverable 6's Open Items

**Partition-maintenance mechanism (was open, now resolved):** `pg_partman`, not a hand-rolled cron script. Rationale: it's a mature, widely-deployed Postgres extension built for exactly this, it handles both ahead-of-need partition creation *and* (when you're ready to configure it) retention/archival, and it fails loudly (a monitorable job status) rather than silently. `db-migrations/_post-migration/configure-pg-partman.sql` registers all six partitioned tables from Deliverable 6 with `create_parent()` — 4 months' headroom for the monthly ledgers, 2 years' headroom for the yearly `premium_invoice`. This runs as a **post-migration** CD pipeline step (see `deploy-staging`/`deploy-production` jobs), never as a Flyway migration itself or a DB-bootstrap script, because `create_parent()` requires its target table to already exist.

**`audit_log`/ledger retention (still open, but with a safe default applied):** every table's `p_retention` is left unset in the pg_partman config — meaning partitions are created ahead of need but **never auto-dropped** until Legal/Compliance confirms real retention periods. This is the safe direction to default in (over-retaining is a storage cost; under-retaining a TIRA-relevant audit trail is a compliance incident) — but it is still an open item, not a decision I'm making on your behalf.

**`app_role` provisioning (was open, now automated *and* CI-checked):** created via `postgres/init/01-create-app-role.sql` at container/environment bootstrap (infrastructure-as-code, not a manual step), and the `db-migration-validation` CI job now includes an explicit assertion — `SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = 'app_role'` — that **fails the build** if either is true. This turns Deliverable 6's finding (RLS is silently void under the wrong role) from a documentation note into something that can't regress unnoticed.

**RLS itself (Deliverable 6 §5) — still genuinely open, not resolved here.** I haven't assumed an answer; the CI check above and the role provisioning both exist *in case* you keep RLS, and are cheap to remove if you don't.

---

## 4. CI/CD Pipeline (`.github/workflows/ci-cd.yml`)

Nine jobs, roughly in dependency order: **build-and-unit-test** → **modulith-verify** (runs `ApplicationModules.verify()` plus the `NoCrossModuleJoinTest`/`NoCircularDependencyTest` conventions from Deliverable 2 Rev 2 — a broken module boundary fails CI, not code review) → **contract-validation** (runs the *exact* `openapi-spec-validator` and `@asyncapi/cli validate` commands used manually in Deliverables 4 and 5 — now permanent regression gates, not one-off checks) → **db-migration-validation** (the exact fresh-database migration run-through from Deliverable 6, plus the `app_role` privilege assertion from §3 above) → **integration-tests** (Testcontainers-based, no shared service containers needed) → **security-scan** (gitleaks for secrets, OWASP dependency-check, Trivy for filesystem/container CVEs) → **build-image** (pushed to GHCR, tagged by commit SHA and branch/tag) → **deploy-staging** (auto, on merge to `main`) → **deploy-production** (tag-triggered, gated behind a GitHub Environment requiring manual reviewer approval — appropriate given this is a regulated financial platform, not just a good habit).

Both deploy jobs run Flyway migrations, then the pg_partman post-migration script, then the actual rolling deploy — in that order, matching the dependency §3 described.

---

## 5. Monitoring, Logging & Observability

**Structured logging:** JSON via Logback, with `tenant_id`, `trace_id`, and `request_id` in MDC on every log line. The `trace_id` is the *same* value returned in every RFC 7807 `ProblemDetails.traceId` (Deliverable 4) — a customer-facing error and the corresponding log line are one lookup apart, not a manual correlation exercise for support staff.

**Distributed tracing:** OpenTelemetry, exported to Tempo. Worth doing even inside a monolith — a request that crosses `policy` → an event listener in `finaccounting` → a Camunda service task calling `payment` is exactly the kind of flow that's opaque without a trace, module boundaries or not.

**Metrics:** Micrometer + Spring Boot Actuator → Prometheus. Beyond generic JVM/HTTP metrics, the alert rules in `observability/alert-rules.yml` are deliberately tied to specific things earlier deliverables flagged as failure-prone, not generic infrastructure noise:
- `FailedEventBacklogGrowing` — any growth in `audit.failed_event` (Deliverable 5 §4's dead-letter table) pages immediately, since it represents a domain side-effect that never happened.
- `FieldReceiptReconciliationOverdue` — the offline-receipt SLA breach from Deliverable 2 Rev 2 B4.
- `SurrenderMaturityProcessStuck` — a Camunda process instance stuck past an hour in the surrender/maturity choreography (Deliverable 3 Rev 2 §12) — the case where `policyloan` never replies to `PolicySurrenderInitiated`.
- `PgPartmanMaintenanceFailed` / `PartitionHeadroomLow` — direct monitoring of the mechanism resolved in §3, so a failure there is caught in hours, not discovered via a hard insert failure in production.
- `RlsBypassRoleDetected` — a scheduled query against `pg_roles` in production, not just a one-time CI check, since a manual `ALTER ROLE` months from now could still silently reintroduce the exact gap Deliverable 6 found.
- `MobileMoneyGatewayErrorRateHigh`, `PostgresDiskSpaceLow` — standard operational coverage.

**Log aggregation:** Loki + Promtail, paired with the same Grafana instance as metrics — one pane for logs, metrics, and traces rather than three separate tools, appropriate for a platform this size rather than a full ELK stack.

**Health checks:** Spring Boot Actuator `/health`, with Spring Modulith's module-level health indicators surfaced individually — so a `finaccounting`-specific issue (still provisional, per every prior deliverable) doesn't read as "the whole app is unhealthy" on a dashboard.

---

## 6. Open Items Before Deliverable 8 (Implementation Roadmap)

1. **Confirm Camunda 7 embedded vs. Camunda 8/Zeebe** (§1) — this materially affects the build order and infrastructure Deliverable 8 will propose.
2. **RLS decision** — still carried forward from Deliverable 6, unresolved.
3. **`audit_log`/ledger retention periods** — still a Legal/Compliance input, now with a safe non-committal default in place.
4. Deployment approval reviewers for the `production` GitHub Environment need to be named — an org/process decision, not an architecture one, but it blocks the CD pipeline from actually being usable end-to-end.

*Holding here per your process — once reviewed, I'll proceed to Deliverable 8 (Implementation Roadmap: prioritized module build order, dependency graph, milestone definitions with acceptance criteria) — the final Phase 0 artifact.*
