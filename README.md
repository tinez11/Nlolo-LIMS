# Digital Life Insurance Core Platform (Tanzania)

**Status: Phase 0 (Architecture & Design) complete — all 8 deliverables produced and awaiting your review.**

**Base groupId confirmed:** `tz.co.nlolo.lifeplatform` (reverse-DNS of `nlolo.co.tz`). Every module's Java package root will be `tz.co.nlolo.lifeplatform.<module>` once code generation starts.

This folder contains everything produced during Phase 0. **No application business-logic code has been generated yet** — per the AI Coding Rules this engagement is running under, code generation only begins once you explicitly say the word **"proceed"** (in whichever tool/session you continue this with — this rule travels with the project, not with any one chat session).

## Layout

- `docs/` — the eight Phase 0 deliverables in order, plus ADR-001 (Party abstraction decision):
  - `01-domain-map.md` — bounded contexts, context map, ubiquitous language
  - `01a-adr-party-abstraction.md`
  - `02-module-architecture.md` — 18 Spring Modulith modules, public APIs, dependency graph
  - `03-aggregate-design.md` — aggregates/entities/value objects per module
  - `04-api-contracts.md` — OpenAPI contract framing, auth matrix, async patterns
  - `05-event-catalog.md` — AsyncAPI event catalog documentation, versioning/delivery policy
  - `06-database-schema.md` — schema design notes, RLS/partitioning findings
  - `07-infrastructure-architecture.md` — Docker Compose, CI/CD, observability walkthrough
  - `08-implementation-roadmap.md` — module build order, milestones, open-item tracker
- `api/openapi/` — 11 validated OpenAPI 3.1 contract files (one per channel-facing module) + `openapi-common.yaml`
- `api/asyncapi-events.yaml` — AsyncAPI 2.6 event catalog, 57 channels/messages, validated with `@asyncapi/cli`
- `db-migrations/` — 16 per-module Flyway `V1__create_<module>_schema.sql` files + `_post-migration/configure-pg-partman.sql`
- `infra/` — `docker-compose.yml` + observability overlay, Postgres and app Dockerfiles, `app_role` bootstrap SQL
- `observability/` — Prometheus, Alertmanager-relevant alert rules, Tempo config
- `ci-cd-workflows/ci-cd.yml` — 9-job pipeline (build → modulith-verify → contract-validation → db-migration-validation → integration-tests → security-scan → build-image → deploy-staging → deploy-production). **Move this to `.github/workflows/ci-cd.yml`** once you're working in this folder locally — it was placed outside `.github/` because that path can't be written remotely.

## Standing open items (see `docs/08-implementation-roadmap.md` §5 for the full table)

1. Camunda 7 (embedded) vs. Camunda 8/Zeebe — blocks the M3 (policy/policyloan) surrender/maturity choreography specifically
2. Row-Level Security keep/revert decision
3. `audit_log`/ledger retention periods (Legal/Compliance input needed)
4. Production deployment approval reviewers (names needed for the GitHub Environment gate)
5. IFRS17 cohort/grouping rules (GMM vs. PAA) — blocks `finaccounting` completion
6. TIRA return catalog/format — blocks `regreporting` completion
7. Statutory limits referenced in Deliverable 2's open questions
8. ~~Base Maven package/groupId~~ — **RESOLVED:** `tz.co.nlolo.lifeplatform`

## Getting started in VS Code

This is documentation and infrastructure config, not a runnable Maven/Gradle project yet — that scaffold (Deliverable 8's "M0 — Platform Bootstrap" milestone) is the first thing generated once code generation is authorized. Until then, this folder is meant to be browsed/reviewed: the `docs/` folder for the design record, `api/` and `db-migrations/` as the contracts everything else must honor, `infra/` and `.github/` as the environment those contracts will run in.
