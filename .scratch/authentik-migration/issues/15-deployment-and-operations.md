# Deploying and operating Authentik

Type: grilling
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

What replaces Keycloak in the deployment (`deploy/server/compose.yml`, `deploy/auth/Dockerfile`, `deploy/keycloak/build-realms.mjs`, `deploy/bootstrap-server.sh`, `deploy/release.sh`, `DEPLOYMENT-GUIDE.md`, the server's `add-user.sh`)?

- **Image:** the stock `ghcr.io/goauthentik/server:2026.8.5` with blueprints mounted, or our own `-auth` image with the blueprints, the CSS, `mark.svg` and the wordmark-free photo baked in, so a release is one versioned artefact?
- **Database:** a separate `authentik` database on the existing Postgres, with connection headroom. Is it in the encrypted backups, and is the restore rehearsal extended to it?
- **First boot:** `AUTHENTIK_BOOTSTRAP_*` from `.env`, the admin account, and the API token for the backend's service account (non-expiring, per-object rights from [How workspaces and job roles map to Authentik groups](10-groups-and-roles.md)).
- **`add-user.sh`:** today it creates the first staff login with `--phone`. Its Authentik equivalent, and how the operator creates regulator logins.
- **Secrets and `ProductionSettingsGuard`:**
  - the renamed settings (`KEYCLOAK_*` become Authentik's)
  - the two bearer secrets from [The webhook contract between Authentik and the backend](08-webhook-contract.md)
  - `STAFF_SIGN_IN_CODE`
  - the issuer with its trailing slash
- **Caddy:** the public route to Authentik, and keeping `/api/internal/*` closed.
- **Memory limits:** a `mem_limit` for the server (about 1 GiB) and the worker (512 MiB, as in dev).
- **The dev-only blueprint** (test users, app-password tokens, the `e2e-sign-in-code` group) from [How e2e tests sign in under Authentik](14-e2e-sign-in.md) must never reach the server. How is that enforced?

## Answer

The user's decisions, 2026-11-19.

Today's facts:
- `deploy/auth/Dockerfile` builds an `-auth` image with the theme and extensions. `release.sh` versions and pushes it.
- `backup.sh` dumps `lifeplatform` and `keycloak` as a pair.
- Caddy serves Keycloak on `AUTH_DOMAIN`, 404s the `master` realm, and the console is reached only by SSH tunnel.
- `add-user.sh` uses `kcadm` and prints a one-time password.
- `remote-release.sh` waits for Keycloak to be healthy before the backend starts.

1. **Image.** Our own `-auth` image, `FROM ghcr.io/goauthentik/server:2026.8.5`, with the production blueprints, the CSS, `mark.svg` and the wordmark-free photo baked in. `release.sh` builds and versions it as today.
   - **Blueprints in two folders:** `deploy/authentik/blueprints/production/` (baked into the image) and `deploy/authentik/blueprints/dev/` (test users, app-password tokens, the `e2e-sign-in-code` group).
   - **The dev folder** is mounted only by the dev compose and never copied into the image.
   - **`smoke.sh`** fails if a dev user such as `staff.underwriter` exists on the server.
2. **Database and backups.**
   - An `authentik` database on the existing Postgres, with `max_connections` raised for Authentik's roughly 50% more connections.
   - `backup.sh` and `restore.sh` handle `authentik` in place of `keycloak`, always paired with `lifeplatform`. `offsite.sh` is unchanged.
   - The open restore rehearsal covers the new pair.
3. **Public exposure on `AUTH_DOMAIN`:**
   - Caddy 404s `/if/admin/*`, and the operator reaches the admin by SSH tunnel.
   - `/api/v3/*` stays reachable, because the sign-in page needs it; every other API call needs a token.
   - A policy on the public sign-in flow refuses superusers, replacing the master-realm block.
   - `/api/internal/*` on the app domain stays closed.
4. **First boot, token and `add-user.sh`:**
   - `bootstrap-server.sh` generates `AUTHENTIK_BOOTSTRAP_PASSWORD` and `AUTHENTIK_SECRET_KEY` into `.env`.
   - A production blueprint creates the backend's service account, with a non-expiring token whose key comes from `.env`. Its role carries only the rights from [How workspaces and job roles map to Authentik groups](10-groups-and-roles.md).
   - **`add-user.sh` keeps its interface** (`--phone`, `--home-branch`, `--party`, new `--workspace staff|agent|customer|regulator`). It uses Authentik's API with the bootstrap token over the private network, and prints a one-time password that must be changed at first sign-in (the `reset_password` attribute plus check stage). Regulator logins are made this way.
5. **Settings, guard and release:**
   - **Renamed settings:** `AUTHENTIK_BASE_URL`, `AUTHENTIK_API_TOKEN`, `AUTHENTIK_ISSUER`, `SIGN_IN_CODE_WEBHOOK_SECRET`, `PASSWORD_CHANGED_WEBHOOK_SECRET`, `STAFF_SIGN_IN_CODE`.
   - **`ProductionSettingsGuard` refuses:**
     - dev defaults for the token and both secrets
     - `STAFF_SIGN_IN_CODE` off
     - an issuer without its trailing slash
   - **`remote-release.sh`** pulls and starts Authentik's server and worker, and waits for them to be healthy before the backend. The healthcheck command (likely `ak healthcheck`) is a build-time check.
   - **`DEPLOYMENT-GUIDE.md`** is rewritten, including the planned signing-key rotation step and the memory limits (server 1 GiB, worker 512 MiB).
- **The guide:** the `DEPLOYMENT-GUIDE.md` rewrite, including the planned signing-key rotation step from [Go or no-go on Authentik](07-go-no-go.md).
