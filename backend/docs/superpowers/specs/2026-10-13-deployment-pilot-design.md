# Deployment phase: the pilot server — design

> Object store: SeaweedFS replaced MinIO on 2026-10-13 (MinIO withdrew its images); read `minio` below as the SeaweedFS `objectstore` service in deploy/server/compose.yml.

Date: 2026-10-13. **Status:** merged ec42fe75 (2026-10-14)

## 1. Goal and decisions

Put the platform on one internet-facing server for a **pilot / UAT**: real TLS, real Keycloak, real SMS, trusted
users only (the team and the insurer's staff). The same server and the same release process become production once
the money rail is real. Nothing here has ever run outside a laptop; this phase builds and exercises the machinery.

Decisions taken (2026-10-13):

| # | Question | Decision |
|---|----------|----------|
| D1 | Hosting | One rented Linux VPS running Docker Compose |
| D2 | Web addresses | Two hostnames on `nlolo.co.tz`: one for the console + API, one for Keycloak. **Both are settings** (`APP_DOMAIN`, `AUTH_DOMAIN`); the subdomain names may change, so nothing hardcodes them. Examples used below: `app.nlolo.co.tz`, `auth.nlolo.co.tz` |
| D3 | Purpose | Pilot/UAT first. Mobile money stays on the mock; premiums recorded as cash/field receipts. A bank-payment mock is planned separately |
| D4 | Invites and resets | SMS portal invites built now; email wired through as configuration and switched on when a provider account and DNS records exist |
| D5 | Login security | Password policy + brute-force lockout on all four realms; **no** two-factor for now (a realm setting later) |
| D6 | Release mechanism | A release script run from the developer's machine (images → registry → SSH to server), built so CI can call the same script later |
| D7 | Names may change | Every hostname, image prefix, sender address and SMS sender name is a setting in `deploy/.env` |
| D8 | Entry point | The bare `https://${APP_DOMAIN}/` opens the **customer portal**; staff use `/staff`, agents `/agents`, TIRA `/regulators`. No realm picker in production |

## 2. What runs on the server

One VPS: Ubuntu 24.04 LTS, at least 4 vCPU / 8 GB RAM / 80 GB SSD. Docker Compose from `deploy/compose.yml`.

| Service | Image | Internet-reachable | Notes |
|---|---|---|---|
| `caddy` | **web image** — Caddy + the built SPA | Yes, 80/443 only | Automatic Let's Encrypt TLS. `${APP_DOMAIN}`: SPA files, and `/api/*` proxied to the backend with `/api` stripped (exactly what the Vite dev proxy does) — one origin, so **no CORS is needed**. `${AUTH_DOMAIN}`: proxied to Keycloak, with `/admin` **blocked** publicly |
| `backend` | **backend image** (existing Dockerfile, tidied) | No | **Exactly one instance**: 32 `@Scheduled` jobs, several with no lock (reminders, statements, bordereau), would double-run on two. Management port 9090 internal only |
| `keycloak` | **auth image** — Keycloak built in production mode, login theme baked in | Via caddy only | `start --optimized`, hostname `https://${AUTH_DOMAIN}`, trusts caddy's forwarded headers |
| `postgres` | existing custom image (PG 16 + pg_partman + pg_cron) | No | Databases `lifeplatform` and `keycloak`, **separate logins** (Keycloak no longer uses the `postgres` superuser) |
| `minio` | MinIO, pinned version | No | Four document buckets, created by a one-shot init container |
| `mock-mobile-money` | WireMock, as in dev | No | Until the real rail or the bank mock replaces it |

Dropped from production: Redis (no code uses it), Mailpit, the SMS mock (NextSMS is real), pgAdmin, the observability
stack (later).

**Token issuer.** `SecurityConfig` builds each realm's decoder with `NimbusJwtDecoder.withIssuerLocation(issuer)`: it
fetches the discovery document from the issuer URL and requires the token's `iss` to match exactly. So the backend must
reach Keycloak at the same `https://${AUTH_DOMAIN}` browsers see. Inside the Docker network, caddy carries the network
alias `${AUTH_DOMAIN}`; the backend's calls resolve to caddy, stay on the box, present a valid certificate, and
issuers match. (The existing dev compose `app` service, with issuers at `http://keycloak:8080`, would reject every
browser token — it has evidently never been run; it is left alone.)

Every image is pinned to an exact version (no `:latest`); every container has `restart: unless-stopped` and log
rotation (10 MB × 5).

## 3. Keycloak

**Version 24 → 26** (exact patch pinned at implementation), dev and production both. Only the latest major is
supported; upgrading before production holds any users is a test-and-switch, later it is a live identity-database
migration. Realm files and the login theme are re-tested on 26 (the theme diverges from the console on purpose and
must keep doing so).

**Realm configuration as code.** `--import-realm` imports a realm only if absent, which is why five `apply-*` scripts
exist. Production instead runs **keycloak-config-cli** on every release, which reconciles Keycloak with
`deploy/keycloak/realms/{staff,agents,customers,regulators}.json` and is safe to re-run. Those files hold **no users
and no secrets**; values are substituted from `deploy/.env`. Differences from the dev realm files:

- `lifeplatform-spa` (public, PKCE S256) in **all four** realms — the regulators realm gains the client it lacks
  today, without which regulator login fails — with redirect URIs and web origins `https://${APP_DOMAIN}/*` only.
- The legacy confidential `lifeplatform-app` client (password grant, `localhost:3000`) is left out.
- `lifeplatform-admin` (customers realm, service account, `manage-users` only) with secret
  `${PORTAL_ADMIN_CLIENT_SECRET}`.
- Password policy `length(10) and notUsername and passwordHistory(3)` and brute-force protection on every realm.
  Two-factor not required (switchable later).
- Each realm's SMTP block from `MAIL_*` settings when email is on; absent otherwise.
- The existing `tenant_id` / `party_id` / `home_branch` mappers and the `unmanagedAttributePolicy: ADMIN_EDIT`
  components block carry over unchanged.

Dev keeps its JSON import and dev users. A **realm parity test** asserts that dev and production realm files define the
same realm roles, the same `lifeplatform-spa` / `lifeplatform-admin` clients and the same protocol mappers, so they
cannot silently diverge.

**People and tenant.** One tenant UUID, fixed in `deploy/.env` (`PLATFORM_TENANT_ID`). The release script creates the
first `staff.admin` on the first deploy only, with a random password printed once and `UPDATE_PASSWORD` required.
Staff and agent logins are created with `deploy/keycloak/add-user.sh <realm> <username> ...` (over SSH), which sets
roles, `tenant_id`, and `home_branch` (staff) or `party_id` (agents, from the console). Customers continue to be
created by the backend's portal invite. The Keycloak admin console is reached only through an SSH tunnel.

## 4. Database, migrations, backups

**Flyway, one history table per module.** All 245 migrations were checked: no psql meta-commands, no explicit
transactions, no non-transactional statements, no duplicate versions within a module. `scripts/migrate.sh` keeps its
interface and its 23-module order; each module becomes a run of the Flyway CLI (pinned Docker image) with
`-schemas=<module>`, history in `<module>.flyway_schema_history`. Re-runs apply only what is new; an edited applied
migration fails on checksum. Migrations run as the database owner; the app still connects as `app_role` (no superuser,
no BYPASSRLS). `configure-db.sh` runs after, unchanged. The pilot database starts empty (no baseline). An opt-in
`migrate.sh local --baseline` marks the hand-migrated dev database's current versions as applied, touching no data.
The CI fresh-database job uses the same script and runs it **twice**; the second run must apply nothing.

**Order on the server:** postgres healthy → migrate → configure-db → backend. A failed migration stops the release
before the backend restarts.

**Backups.** Nightly (host cron) and before every release: `pg_dump` of `lifeplatform` and `keycloak`, compressed,
plus a mirror of the MinIO buckets, copied off the server to S3-compatible object storage (Backblaze B2 or similar,
from `deploy/.env`). Kept: 14 daily, 8 weekly. `deploy/restore.sh <backup>` restores both databases and the
documents. One real restore to a scratch database is on the go-live checklist, with a monthly reminder. Partition
retention stays "never auto-drop" until Legal/Compliance supply periods.

## 5. Backend production settings

A `prod` Spring profile (`application-prod.yml`):

- **Startup guard**: refuses to boot, naming the setting, when any secret holds a known dev placeholder
  (`minioadmin`, `dev-secret-*`, `dev-only-placeholder-secret`, `devapppassword`, `devpassword`) or SMS is live with
  an empty authorization.
- Datasource, MinIO, the four issuers (`https://${AUTH_DOMAIN}/realms/<realm>`), `KEYCLOAK_BASE_URL`, `PORTAL_URL`
  (`https://${APP_DOMAIN}/customers`), SMS and mail all from `deploy/.env`.
- **Email switch** `MAIL_ENABLED` (default false), mirroring SMS's `live`: while off, email notifications are recorded
  FAILED "email not configured" without trying a server. **Mail leaves the readiness health group**, so a mail outage
  no longer reports the app down (this also stops `ActuatorExposureTest` failing whenever Mailpit is down).
- **Time zone `Africa/Dar_es_Salaam`** for the backend container. Development and all testing ran in EAT; six known
  sites use the default zone (the UTC-vs-civil-day finding), and a UTC container would shift their dates at midnight.
- JVM heap sized to the container (`-XX:MaxRAMPercentage`, ~2 GB limit); graceful shutdown.
- SMS live via NextSMS; the allowlist holds the team's numbers during the pilot and is emptied when real customers
  onboard (one setting + restart).
- The Dockerfile gets a `.dockerignore` (no `target/`, no logs), normalises `mvnw` line endings before running it,
  and drops `-q` so build failures are readable.

## 6. SMS portal invites (code feature)

Channel rule:

| Client has | Email on | Email off (pilot) |
|---|---|---|
| Email (with or without phone) | Email link (today) | SMS if a phone exists, else password shown to staff |
| Phone only | SMS one-time password | SMS one-time password |
| Neither | Refused (today) | Refused (today) |

- Text, built from settings: "Your portal login: username +255…, one-time password K7QM-4XPA. Sign in at
  https://${APP_DOMAIN}/customers — you'll set your own password first." Keycloak's existing `UPDATE_PASSWORD`
  required action enforces the change.
- **The password never reaches the database.** The SMS is sent directly through the SMS sender port, not via the
  dispatch queue that stores bodies for later sending; the message log and the customer's inbox keep the body with the
  password masked (`one-time password ••••••••`).
- If the SMS fails (not allowlisted, gateway down), the staff member is shown the one-time password once, as today's
  phone-only path, with the reason. Onboarding never dead-ends.
- "Re-send invite" issues a fresh one-time password the same way — the phone-only customer's forgotten-password route.
- Staff screen reports "Sent by SMS to +255 696 …340" instead of a password. The affected e2e specs change with it.

## 7. Frontend in production

- Built with `VITE_KEYCLOAK_BASE_URL=https://${AUTH_DOMAIN}` and `VITE_API_BASE_URL=/api` into the web image.
- `VITE_REALM_PICKER=false` in production: `/` routes to `/customers`, and unknown paths go to that audience's home
  rather than the picker. Dev keeps the picker (default true). Nothing is deleted.

## 8. Release and operations

**One-time server setup** (`deploy/bootstrap-server.sh` + `deploy/server-setup.md`): Docker Engine + Compose plugin;
a non-root `deploy` user, SSH keys only; firewall 22/80/443; unattended security upgrades; 2 GB swap; DNS `A` records
for `${APP_DOMAIN}` and `${AUTH_DOMAIN}`; `deploy/.env` generated from `deploy/.env.example` with random secrets (never
committed); the nightly backup cron.

**`deploy/release.sh <version>`** (developer's machine):

1. Refuse if the working tree is dirty or the tag exists; tag the commit.
2. Build backend, web and auth images tagged `<version>`; push to `ghcr.io`.
3. Copy `deploy/compose.yml`, migrations and realm files to the server, then on the server, stopping at the first
   failure: backup → pull → postgres + minio healthy → migrate → configure-db → keycloak healthy →
   keycloak-config-cli → first-deploy admin → restart backend + caddy → **smoke check** (`/` serves the SPA, `/api`
   answers 401 unauthenticated, backend readiness UP, `https://${AUTH_DOMAIN}/realms/staff` reports the expected
   issuer).
4. Record the version in `deploy/RELEASED` on the server; print the outcome and the pre-release backup's name.

`deploy/release.sh --rollback <version>` re-points containers at earlier images. Migrations only go forward; undoing
one is a restore of the pre-release backup.

**Watching it:** an external uptime monitor on both hostnames (5-minute checks); `deploy/status.sh` (container
health, disk, last backup age, backend readiness including the existing scheduled-jobs/partition indicator); rotated
Docker logs.

**Docs:** `deploy/README.md` runbook — first deploy, release, rollback, restore, add a user, rotate a secret, change
a hostname. `docs/07-infrastructure-architecture.md` updated: hosting no longer open; its claims of JSON logging and
OpenTelemetry tracing corrected (neither exists).

## 9. Verification

- Tests: startup guard (rejects each placeholder, accepts real values); SMS invite channel table row by row, stored
  body never contains the password, failure falls back to staff display; realm parity; readiness UP with mail off or
  unreachable; migrate twice applies nothing (CI). Affected backend classes and the portal-invite e2e specs, not the
  full suite.
- **Dress rehearsal** before the VPS: `deploy/compose.yml` locally with `${APP_DOMAIN}` / `${AUTH_DOMAIN}` set to
  local names and a local CA certificate, then by hand: release twice (second changes nothing), staff login, issue a
  policy, SMS invite to an allowlisted number, customer login and password change, PDF download, backup, restore to a
  scratch database, rollback.
- **Go-live checklist:** DNS in place, `.env` complete (the guard confirms), first admin password changed, restore
  tested, uptime monitor on, SMS allowlist set.

## 10. Out of scope (recorded so none is lost)

Real email provider signup (wiring is in; switching on is configuration) · staff two-factor · real mobile money and
the bank-payment mock · more than one backend instance (needs scheduler locks) · durable events
(`spring-modulith-starter-jpa`) · Grafana/Prometheus/alerting, JSON logs, tracing · CI-driven deploys (same script,
one more job) · the on-screen rebrand (~30 frontend files, three backend strings, the login theme) · a separate staging
server · maker-checker on live prices · fixing the remaining UTC/civil-day sites in code.

## 11. Implementation stages

1. `prod` profile, startup guard, mail switch, readiness change, time zone, Dockerfile tidy.
2. SMS portal invites (backend + staff screen + e2e).
3. Flyway `migrate.sh` (+ baseline option, CI twice-run).
4. Keycloak 26, production realm files, keycloak-config-cli, `add-user.sh`, parity test; frontend picker switch.
5. `deploy/` — compose, Caddy web image, auth image, release/rollback, backup/restore, bootstrap, status, runbook;
   the dress rehearsal.
