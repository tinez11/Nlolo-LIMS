# 09 — Local Development

A runbook for running the platform on a development machine. Postgres, Authentik (sign-in), Redis, SeaweedFS (the document store),
the mobile-money and SMS stubs, and Mailpit all run as Docker containers; the backend and the console run on the host
(see gotcha 2 below for why).

## Prerequisites

- **Docker** (with Compose v2 — `docker compose`, not the standalone `docker-compose` binary) and
  enough free RAM to run six-plus containers plus the JVM.
- **Node** (for any frontend tooling this repo's `package.json` scripts assume — not required to
  run the backend alone).
- **A JDK 21**, exported as `JAVA_HOME` before running Maven. On the main dev machine it is
  `/c/Users/USER/.jdks/jdk-21.0.12.1+1` (already on `PATH`; `java -version` says 21). Do **not** use the
  VS Code Java extension's bundled runtime any more: since extension 1.58.0 (2026-10) it ships Java **25**,
  not 21, and its path changes with every extension update.
- **Do NOT install Postgres or Authentik natively.** They run as containers on host ports `15432`
  and `9100` respectively (Postgres is published on `15432`, not the standard `5432`, specifically
  because a native Postgres install will silently win that port instead of failing to bind —
  `application-local.yml`'s datasource URL already points at `15432` to match; Authentik's server
  publishes its internal `9000` as host `9100` — see `infra/docker-compose.yml`). If you
  already have a local Postgres or another service running on either port, stop it before
  bringing this stack up, or the app will silently talk to the wrong database with a confusing
  `password authentication failed` error.
- **No local `psql` or Flyway install is required.** `scripts/migrate.sh local` runs the Flyway CLI
  as a container, and `scripts/configure-db.sh local` runs `psql` inside the `postgres` container.

## Startup

```bash
# from backend/
cd infra && docker compose up -d postgres authentik-server authentik-worker objectstore redis mock-mobile-money mock-sms-gateway mailpit && cd ..
cd infra && docker compose up objectstore-init && cd ..   # one-shot: creates the four buckets
scripts/migrate.sh local          # nothing applies schema automatically -- see below
scripts/configure-db.sh local     # partitions + every pg_cron job; safe to re-run, any time
export JAVA_HOME="/c/Users/USER/.jdks/jdk-21.0.12.1+1"
./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local   # API on :8080, about a minute to start
scripts/seed-dev-data.sh          # in a second terminal, once the app is up

# the console (staff, agents and customers), from frontend/ in a third terminal
npm install                       # first time only
npm run generate:api              # after any change under backend/api/openapi -- the types are not committed
npm run dev                       # http://localhost:5173
```

After pulling a change that adds a migration, run `scripts/migrate.sh local` (and `configure-db.sh` if a
`_post-migration` file changed) and restart the backend -- the running app does not pick up schema changes.

`pgadmin` is intentionally not in that container list — it lives behind the `tools` Compose
profile and is optional. Start it with:

```bash
cd infra && docker compose --profile tools up -d pgadmin && cd ..
```

## Reset sequence

`scripts/migrate.sh local` is safe to re-run (see gotcha 1), so a reset is only needed when you
actually want an empty database. To get back to a clean slate:

```bash
cd infra
docker compose --profile tools down -v   # -v drops the named volumes, including postgres-data
docker compose up -d postgres authentik-server authentik-worker objectstore redis mock-mobile-money mock-sms-gateway mailpit
docker compose up objectstore-init       # DO NOT SKIP -- see below
cd ..
scripts/migrate.sh local
scripts/configure-db.sh local
```

**Do not skip `objectstore-init`.** `-v` drops `objectstore-data`, and the four buckets
(`policy-documents`, `kyc-evidence`, `underwriting-evidence`, `claim-evidence`) go with it.
Nothing in the application recreates them -- there is no `makeBucket` call anywhere in the Java
code, by design, because a service that creates its own storage on demand hides a
misconfiguration instead of reporting it. `objectstore-init` is a one-shot container running SeaweedFS's own
`weed shell` that exists precisely for this and is the only thing that makes them.

Both command lists above omitted it until 2026-09-28, which is a real trap rather than a
cosmetic gap: **every other step succeeds without it.** Migrations apply, the seeder runs to
completion, the console loads, and the failure surfaces much later as an upload that silently
never lands -- `staff-party-kyc` waiting on "Evidence uploaded:" that never appears. Same shape
as the `configure-db.sh` warning above, and for the same reason: nothing fails at startup, the
work simply never happens.

**Do not skip `configure-db.sh`.** It installs what the numbered migrations cannot: pg_partman's
monthly partitions and every scheduled job (billing sweep, commission close, offer expiry and
reminders, loan interest). Without it nothing fails at startup -- the work simply never happens,
and the monthly ledgers run out of partitions at the end of the last hand-written month. Unlike
`migrate.sh` it is idempotent, so re-run it whenever a `_post-migration` file changes. The
backend's health endpoint reports anything it finds missing (`scheduledJobs`).

`down -v` removes `postgres-data`, `redis-data`, and `objectstore-data` — Postgres schema, cached
sessions, and uploaded documents all go with it. There is no partial-reset option; the compose
project has one Postgres instance shared by every module's schema, so "reset just one module" is
not a thing `migrate.sh` or Compose supports.

If `scripts/migrate.sh local` fails partway through with `relation "..." already exists`, that
almost always means you skipped the volume drop above and are re-running against a database that
already has (some of) the schema applied — not a bug in the migration files themselves.

## Four things that will otherwise cost you an afternoon

**1. Migrations do not run automatically.** The application does not migrate on startup, so
`docker compose up` on a fresh volume leaves an entirely empty database behind a running app, and
every request fails on a missing relation — an error that names nothing about the real cause.
`scripts/migrate.sh local` is the path.

Since 2026-10-13 it drives the **Flyway CLI** (as a pinned container, so nothing needs installing),
one schema-history table per module in `public.flyway_history_<module>`. It is therefore **safe to
re-run**: only migrations not yet applied run, and an already-applied migration whose file has been
edited since stops the run on a checksum mismatch rather than diverging environments quietly. It
used to be a plain `psql` loop that could migrate a database exactly once — which is why the CD
pipeline's "Run Flyway migrations against production" step would have failed on every deploy after
the first.

A database migrated BEFORE Flyway (this dev one) needs `scripts/migrate.sh local --baseline` once,
which records each module's current version as applied without running anything. Running it on a
database Flyway already manages is refused.

**2. Run the application on the HOST, not as the compose `app` service, whenever a browser is
involved.** `application.yml` defaults Authentik's issuer to
`http://localhost:9100/application/o/lifeplatform/` (trailing slash included), which is what your browser and
Authentik both use. A token's `iss` must match it exactly, so an app inside Docker reaching Authentik by a container
name would refuse every browser's token -- an opaque 401 with a correct-looking login. Running on the host makes every
URL agree, matches this project's standing rule against running Maven in Docker, and is where Authentik's sign-in code
and password-changed notices call back (`host.docker.internal:8080`).

**3. Two worktrees (or a worktree and the main checkout) running `docker compose up` at the same
time will collide.** `docker compose config` in this directory reports `name: infra` — Compose
derives the project name from the current directory's basename (`infra`), not from the worktree's
full path. Every checkout on the machine has an `infra/` subdirectory, so every one of them gets
the *same* project name unless told otherwise, and therefore the same container names and the same
named volumes (`infra_postgres-data`, `infra_redis-data`, `infra_objectstore-data`). Two worktrees doing
`cd infra && docker compose up` concurrently will fight over one Postgres container instead of
each getting its own — one side's migrations can land in the other side's database, or `down -v`
in one worktree can silently wipe the schema another worktree is relying on. Mitigation: give each
worktree its own project name, either by exporting `COMPOSE_PROJECT_NAME=<worktree-name>` before
running compose commands, or by passing `-p <worktree-name>` on every `docker compose` invocation
in that worktree.

**4. The dev users alone are NOT enough to log in as a customer or an agent — you must run
the seeder.** The dev blueprint (`deploy/authentik/blueprints/dev/dev-users.yaml`, which Authentik's worker applies
as it starts) sets each test user's `tenant_id` attribute, but deliberately sets **no** `party_id`: a party's UUID does
not exist until `POST /parties/individuals` creates it, so only `scripts/seed-dev-data.sh` (step 4, "Write `party_id`
onto the Authentik logins") can fill it in. The users are `state: created`, so re-applying the blueprint leaves the
`party_id` alone. Skip the seeder and `customer.owner` still
authenticates perfectly — Authentik issues a valid token, `TenantContextFilter` accepts it because
`tenant_id` is present — and then **every ownership-checked endpoint returns `403`**:
`GET /policies/{id}`, `GET /claims`, `GET /claims/{id}/evidence/{ref}`, `GET /parties/{id}`,
`GET /policies/{id}/loans`, and the agent hierarchy endpoints. The failure mode looks like a
permissions bug or a broken blueprint; it is neither. Recognize it by decoding the access token
(`jwt.io`, or the token `scripts/seed-lib.sh`'s `token_for` mints): if it has `tenant_id` but no
`party_id`, you skipped or half-ran the seeder. The fix is not to hand-edit the user in the admin
console — run the seeder, and if it refuses with "Already seeded", drop the volume per the reset
sequence above and start clean. (`set_party_id` in `scripts/seed-lib.sh` writes one by hand -- but the `party_id` has
to match a real party row, so guessing one just moves the `403` to a `404`.)

## Consoles

| Service | URL | Credentials |
|---|---|---|
| Console (one sign-in at `/` for staff, agents, customers) | http://localhost:5173 | the dev users -- `deploy/authentik/blueprints/dev/dev-users.yaml`, password `devpassword` |
| Application API | http://localhost:8080 | bearer token from Authentik (`token_for` in `scripts/seed-lib.sh`) |
| Authentik admin | http://localhost:9100/if/flow/default-authentication-flow/?next=/if/admin/ | `akadmin` / `devadmin` |
| pgAdmin | http://localhost:5050 | `dev@nlolo-lifeplatform.tz` / `devadmin` (server pre-registered) |
| Mailpit (outbound mail) | http://localhost:8025 | none |
| Document store (S3 API, no console) | http://localhost:9000 | `minioadmin` / `minioadmin` (see the note below) |
| WireMock (mobile money) | http://localhost:8082 | none |
| WireMock (SMS gateway) | http://localhost:8083 | none |

**The dev document store is SeaweedFS (2026-10-19).** MinIO withdrew public distribution on
2026-10-13 -- its Docker Hub repositories are gone, `dl.min.io` answers 410, quay.io demands credentials -- so
`minio/minio:latest` pulled on no new machine. The dev stack now runs SeaweedFS (`objectstore`), as the tests
(`ObjectStoreContainer`) already did, and the server does natively (`deploy/server`). The platform still talks to it
through the `io.minio` S3 client on **host port 9000 with the same dev credentials**, so `application.yml`'s dev
defaults are unchanged; the credentials live in `infra/objectstore/s3-dev.json`. There is no web console -- list a
bucket with `rclone` or the S3 API. Documents stored in the old MinIO volume were copied across with rclone and
compared file by file; `infra_minio-data` was left in place, unused, and can be deleted once nothing is missed.

**Sign-in is Authentik (since 2026-11-19; Keycloak until then).** Its whole configuration is the blueprints in
`deploy/authentik/blueprints/` (production's flows, provider and look; dev's test users), which the worker applies at
start and whenever a file's content changes. A Windows bind mount sends no file events into the container, so after
editing a blueprint apply it yourself -- `docker exec infra-authentik-worker-1 ak apply_blueprint
/blueprints/lifeplatform/lifeplatform.yaml` (from PowerShell; Git Bash rewrites the path) -- or restart the worker.
Like any database-backed server, an Authentik upgrade migrates its database in place: `pg_dump` the `authentik`
database before changing the version in `infra/docker-compose.yml`.

pgAdmin's registered connection uses the `postgres` superuser deliberately: `app_role` is
`NOSUPERUSER NOBYPASSRLS`, so browsing as it shows **zero rows** in every tenant-scoped table
until you `SET app.current_tenant_id`. That is tenant isolation working, not a broken database.
