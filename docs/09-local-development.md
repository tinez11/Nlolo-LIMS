# 09 — Local Development

A runbook for running the platform on a development machine. Postgres, Keycloak, Redis, MinIO,
the mobile-money stub, and Mailpit all run as Docker containers; the application itself runs on
the host (see gotcha 2 below for why).

## Prerequisites

- **Docker** (with Compose v2 — `docker compose`, not the standalone `docker-compose` binary) and
  enough free RAM to run six-plus containers plus the JVM.
- **Node** (for any frontend tooling this repo's `package.json` scripts assume — not required to
  run the backend alone).
- **The VS Code Java extension's bundled JRE 21**, at
  `/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64`. Export
  it as `JAVA_HOME` before running Maven; do not rely on a separately-installed JDK unless you
  know it is also Java 21.
- **Do NOT install Postgres or Keycloak natively.** They run as containers on `5432` and `8081`
  respectively (Keycloak's container publishes its internal `8080` as host `8081` — see
  `infra/docker-compose.yml`). A native Postgres or Keycloak install listening on the same port
  will fail to bind, or worse, silently win the port and have the containerized service fail
  instead, producing a confusing "why is my data missing" investigation. If you already have a
  local Postgres or Keycloak service running, stop it before bringing this stack up.
- **No local `psql` install is required.** `scripts/migrate.sh local` runs `psql` inside the
  `postgres` container itself.

## Startup

```bash
cd infra && docker compose up -d postgres keycloak minio redis mock-mobile-money mailpit && cd ..
scripts/migrate.sh local          # nothing applies schema automatically -- see below
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local
scripts/seed-dev-data.sh          # in a second terminal, once the app is up
```

`pgadmin` is intentionally not in that container list — it lives behind the `tools` Compose
profile and is optional. Start it with:

```bash
cd infra && docker compose --profile tools up -d pgadmin && cd ..
```

## Reset sequence

`scripts/migrate.sh local` is a first-run-only tool (see gotcha 1). To get back to a clean slate:

```bash
cd infra
docker compose --profile tools down -v   # -v drops the named volumes, including postgres-data
docker compose up -d postgres keycloak minio redis mock-mobile-money mailpit
cd ..
scripts/migrate.sh local
```

`down -v` removes `postgres-data`, `redis-data`, and `minio-data` — Postgres schema, cached
sessions, and uploaded documents all go with it. There is no partial-reset option; the compose
project has one Postgres instance shared by every module's schema, so "reset just one module" is
not a thing `migrate.sh` or Compose supports.

If `scripts/migrate.sh local` fails partway through with `relation "..." already exists`, that
almost always means you skipped the volume drop above and are re-running against a database that
already has (some of) the schema applied — not a bug in the migration files themselves.

## Four things that will otherwise cost you an afternoon

**1. Migrations do not run automatically.** `pom.xml` contains neither Flyway nor Liquibase, so
`docker compose up` on a fresh volume leaves an entirely empty database behind a running app, and
every request fails on a missing relation — an error that names nothing about the real cause.
`scripts/migrate.sh local` is the only path. It is also NOT idempotent: re-running it against an
already-migrated database fails on plain `CREATE TABLE`. To start clean, drop the volume
(`docker compose --profile tools down -v`), bring it back up, and re-migrate.

**2. Run the application on the HOST, not as the compose `app` service, whenever a browser is
involved.** `application.yml` defaults the Keycloak issuers to `http://localhost:8081/realms/...`,
which is what your browser and Keycloak both use. The compose `app` service overrides them to
`http://keycloak:8080/...` for in-network use. Run the app in Docker and the browser is redirected
to `localhost:8081`, Keycloak stamps the token `iss: http://localhost:8081/...`, and the app
rejects it because it expects `keycloak:8080` — an opaque 401 with a correct-looking login. Running
on the host makes every URL agree, and matches this project's standing rule against running Maven
in Docker.

**3. Two worktrees (or a worktree and the main checkout) running `docker compose up` at the same
time will collide.** `docker compose config` in this directory reports `name: infra` — Compose
derives the project name from the current directory's basename (`infra`), not from the worktree's
full path. Every checkout on the machine has an `infra/` subdirectory, so every one of them gets
the *same* project name unless told otherwise, and therefore the same container names and the same
named volumes (`infra_postgres-data`, `infra_redis-data`, `infra_minio-data`). Two worktrees doing
`cd infra && docker compose up` concurrently will fight over one Postgres container instead of
each getting its own — one side's migrations can land in the other side's database, or `down -v`
in one worktree can silently wipe the schema another worktree is relying on. Mitigation: give each
worktree its own project name, either by exporting `COMPOSE_PROJECT_NAME=<worktree-name>` before
running compose commands, or by passing `-p <worktree-name>` on every `docker compose` invocation
in that worktree.

**4. The Keycloak realms alone are NOT enough to log in as a customer or an agent — you must run
the seeder.** `keycloak/customers-realm.json` and `keycloak/agents-realm.json` set each test user's
`tenant_id` attribute, but deliberately set **no** `party_id`: a party's UUID does not exist until
`POST /parties/individuals` creates it, so only `scripts/seed-dev-data.sh` (step 4, "Write
`party_id` back into Keycloak") can fill it in. Skip the seeder and `customer.owner` still
authenticates perfectly — Keycloak issues a valid token, `TenantContextFilter` accepts it because
`tenant_id` is present — and then **every ownership-checked endpoint returns `403`**:
`GET /policies/{id}`, `GET /claims`, `GET /claims/{id}/evidence/{ref}`, `GET /parties/{id}`,
`GET /policies/{id}/loans`, and the agent hierarchy endpoints. The failure mode looks like a
permissions bug or a broken realm import; it is neither. Recognize it by decoding the access token
(`jwt.io`, or `curl` the token endpoint and look at the payload): if it has `tenant_id` but no
`party_id`, you skipped or half-ran the seeder. The fix is not to hand-edit the user in the admin
console — run the seeder, and if it refuses with "Already seeded", drop the volume per the reset
sequence above and start clean. (Hand-editing *would* work — that is what
`unmanagedAttributePolicy: ADMIN_EDIT` in the realm files is for, see `keycloak/README.md` — but
the `party_id` has to match a real party row, so guessing one just moves the `403` to a `404`.)

## Consoles

| Service | URL | Credentials |
|---|---|---|
| Application API | http://localhost:8080 | bearer token from Keycloak |
| Keycloak admin | http://localhost:8081 | `admin` / `devadmin` |
| pgAdmin | http://localhost:5050 | `dev@nlolo-lifeplatform.tz` / `devadmin` (server pre-registered) |
| Mailpit (outbound mail) | http://localhost:8025 | none |
| MinIO console | http://localhost:9001 | `minioadmin` / `minioadmin` |
| WireMock (mobile money) | http://localhost:8082 | none |

pgAdmin's registered connection uses the `postgres` superuser deliberately: `app_role` is
`NOSUPERUSER NOBYPASSRLS`, so browsing as it shows **zero rows** in every tenant-scoped table
until you `SET app.current_tenant_id`. That is tenant isolation working, not a broken database.
