# 10: Production deployment

**What to build:** A release ships Authentik instead of Keycloak, and the operator runs it as before.

- **The `-auth` image:** built from stock authentik 2026.8.5, with the **production** blueprints, CSS and images baked in. Built and versioned by the release script. The dev blueprints are never in it.
- **Server compose:** a server (1 GiB) and a worker (512 MiB), with no Docker socket and no root.
- **Database:** an `authentik` database on the existing Postgres, with connection headroom raised and the SSL mode set explicitly.
- **Backups:** backup and restore pair `authentik` with `lifeplatform`, in place of `keycloak`.
- **Caddy on the auth domain:** the admin UI is closed (404) and reached by SSH tunnel. The flow and API paths the sign-in page needs stay open. The internal callback paths on the app domain stay closed.
- **First boot:** generates Authentik's secret key, the bootstrap password and the backend's token key.
- **`add-user.sh`:** keeps its flags (including job roles, now written to the `job_roles` attribute) and gains `--workspace staff|agent|customer|regulator`. It prints a one-time password, with a forced change at first sign-in. Regulator logins are made this way.
- **The release script:** waits for the server and worker to be healthy before the backend. Confirm the healthcheck command early.
- **The smoke check:** fails if any dev test user exists.
- **Settings:** renamed (`AUTHENTIK_*`, the two webhook secrets, `STAFF_SIGN_IN_CODE`). `ProductionSettingsGuard` refuses dev defaults, the code switch off, and an issuer without its trailing slash.
- **The deployment guide** is rewritten, including **planned signing-key rotation**: everyone is signed out, so announce it and pick a quiet hour.
- **Blueprint drift** (found in ticket 01): Authentik re-applies a blueprint only when the file's content changes. A setting changed by hand in the admin stays changed until then. The guide must say so: change configuration only through the blueprints, and after editing one, apply it (or restart the worker) and check its status.
- **The admin route** (found in ticket 02): the public sign-in flow refuses superusers, so the bootstrap admin signs in through the stock `default-authentication-flow` (`/if/flow/default-authentication-flow/?next=/if/admin/`). Caddy must close that flow's path and API calls on the public auth domain as well as the admin UI, so it is reachable only through the SSH tunnel; otherwise any login could use it to skip the platform's flow.
- **Token revocation on the app domain** (found in ticket 02): Authentik 2026.8.5's revoke endpoint sends no CORS headers, so the console revokes at sign-out through its own origin, `/oauth/revoke/`. Caddy on the app domain must forward exactly that path to Authentik's `/application/o/revoke/` (the Vite dev server does the same in dev). Without it, sign-out still ends the session, but the refresh token lives on for up to 30 idle minutes.
- **`AUTHENTIK_API_TOKEN`** (found in ticket 03) must be set, with the same value, on the backend and on the Authentik server and worker. The blueprint keys the `lifeplatform-backend` service account's token from it, with no default: unset, Authentik generates a key nobody holds and every login change fails. First boot generates it. `ProductionSettingsGuard` already refuses the dev value.
- **The sign-in code settings** (found in ticket 05) on the Authentik server and worker:
  - `STAFF_SIGN_IN_CODE` (the same value as the backend's)
  - `SIGN_IN_CODE_WEBHOOK_SECRET` (the same as the backend's)
  - `LP_BACKEND_URL`, the backend's address on the internal Docker network, e.g. `http://app:8080`. The dev default, `host.docker.internal`, is wrong there.

  Both `/internal/staff-sign-in-code/sms` and `/internal/staff-sign-in-code/check` stay behind Caddy's `/api/internal/*` block. Authentik reaches them directly on the internal network.
- **`PASSWORD_CHANGED_WEBHOOK_SECRET`** (found in ticket 06), the same on the backend and on the Authentik worker, which sends the notification. `/internal/password-changed` stays behind Caddy's internal block.
- **The password reset page** (found in ticket 07) needs these on the backend even with the public quote link switched off:
  - `AUTHENTIK_PUBLIC_URL`, Authentik's address as browsers reach it; recovery links are put on it
  - the insurer's tenant id
  - the Turnstile secret
  - the visit secret

  The quote link's settings carry the last three, and they're unset on the server today (see the "public quote prod wiring gap" note). `/public/password-reset` and `/check` must stay reachable through Caddy, as `/public/*` is.
- **`LP_APP_ORIGIN`** must be set on the server and worker: it builds both the sign-in return address and the sign-out return address in the blueprint.
- **The backend's settings:** `AUTHENTIK_ISSUER` and `AUTHENTIK_AUDIENCE` replace `KEYCLOAK_ISSUER`. The backend currently falls back to the localhost issuer if they aren't set, so the server compose must set them, and `ProductionSettingsGuard` must refuse the localhost default.

Spec: Part 5. The decision is in ticket 15 of `.scratch/authentik-migration/`.

**Blocked by:** 03, 04, 05, 06, 07, 08

**Status:** ready-for-agent

Re-scoped 2026-11-20 to the native install (see the comment below); the dry run happens on the real server (Q6).

- [ ] On a fresh Debian 13 server, `bootstrap-server.sh` then `release.sh` come up from nothing: Authentik builds from source, the production blueprint applies, and `platform.admin` signs in with the texted code
- [ ] A release ships no dev blueprint, and the smoke check fails when a dev user is present
- [ ] Backup then restore of the `lifeplatform` and `authentik` pair brings back a working sign-in
- [ ] Everything on the auth domain but the sign-in pages answers 404 through Caddy, and every sign-in journey still works (sign-in with the code, sign-out, Change password, `/reset`)
- [x] The production guard refuses each dev default and each bad setting it names (`ProductionSettingsGuardTest`, 11 tests)
- [ ] A rollback puts the previous release and its Authentik build back
- [ ] The guide's steps were followed literally on the server

**Added 2026-11-20 (ticket 08):** the `-auth` image bakes `deploy/authentik/branding/` in at `/media/public/lifeplatform/`, where the brand finds `lifeplatform/brand.jpg`, `mark.svg` and `favicon.svg`. A media volume mounted over `/media` in production would hide them: mount it elsewhere, or copy the files into it at first boot.

## Comments

**2026-11-20, re-scoped: a native install under systemd, no Docker on the server.** The user's decisions:
- **Q1 a:** finish ticket 10, then ticket 11, then deploy from `main`.
- **Q2 c:** run everything natively under systemd, Authentik included, against the recommendation to keep Docker. Upstream supports only Docker and Kubernetes, so Authentik is built from source.
- **Q3 a:** account lockout, carried over from Keycloak (D5). It is still to be shaped (Q3b): per-username lockout without revealing a correct password needs a two-step sign-in page.
- **Q4:** Debian 13 by default, the OS upstream builds on.
- **Q5 b:** Authentik builds on the server.
- **Q6:** the dry run happens on the real server, not on this machine.

The "What to build" list above still holds where it is about Authentik. Its Docker-specific lines (the `-auth` image, the server compose, the containers' memory limits) are replaced by:

- **Services** (systemd, each with its own user, on loopback only):
  - `postgresql@16-main` (PGDG apt, with partman and pg_cron, `max_connections=200`)
  - `seaweedfs` (the release binary; its volume port moves off 8080)
  - `platform-mock-mobile-money` (WireMock standalone)
  - `authentik-server` (`127.0.0.1:9000`) and `authentik-worker` (`127.0.0.1:9001`): they share one host, so the worker gets its own ports
  - `platform-backend` (`java -jar`)
  - `caddy` (apt), the only service facing the internet
- **Authentik, built on the server** by `install-authentik.sh <version>`, once per Authentik version, following upstream's `lifecycle/container/Dockerfile` at that tag:
  - the web UI with Node 26 and pnpm 11.20
  - the server binary with Rust 1.97.1, plus cmake, clang and Go for its FIPS crypto library
  - the Python environment with `uv` and Python 3.14.7
  - it runs from the checkout as its working directory (`./web/dist` and `./lifecycle` are relative); `blueprints_dir`, `storage.file.path` and `cert_discovery_dir` are moved by environment settings
- **Releases:** the backend jar and the console are built on the developer's machine (both are platform-independent) and copied up. The server keeps `/opt/platform/releases/<version>`, with `current` as a symlink, so a rollback is a symlink and a restart.
- **Settings:** one `.env` stays the operator's only file. The release renders a root-only environment file for each service from it.
- **Scripts:** `migrate.sh` and `configure-db.sh` gain a `native` mode (the Flyway CLI and `psql` as the postgres user). `backup.sh`, `restore.sh`, `add-user.sh` (Authentik's `manage shell`), `smoke.sh` and `status.sh` swap `docker compose exec` for native commands.
- **Already built, and carried over:**
  - `ProductionSettingsGuard`: `/reset` always needs a real Turnstile secret, a visit secret and the insurer; the issuer needs its trailing slash; the public Authentik URL must be https. Tests updated.
  - the console's build settings: Authentik's issuer (it was still using the Keycloak names) and the Turnstile site key
  - the Caddy allow-list on the auth domain, from a recording of every sign-in journey, and the `/oauth/revoke/` forward
  - the backup pairing (`authentik` with `lifeplatform`)
  - `add-user.sh`'s rules (`--workspace`, customers, `job_roles`, rights for the backend's role) and `AddUserScriptRolesTest`
  - the smoke checks (dev users, blueprint status, everything else on the auth domain answering 404)
  - the first administrator renamed `platform.admin`: `staff.admin` is a dev user's name, which the smoke check refuses
