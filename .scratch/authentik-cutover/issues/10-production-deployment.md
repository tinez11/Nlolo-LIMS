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
- **The backend's settings:** `AUTHENTIK_ISSUER` and `AUTHENTIK_AUDIENCE` replace `KEYCLOAK_ISSUER`. The backend currently falls back to the localhost issuer if they aren't set, so the server compose must set them, and `ProductionSettingsGuard` must refuse the localhost default.

Spec: Part 5. The decision is in ticket 15 of `.scratch/authentik-migration/`.

**Blocked by:** 03, 04, 05, 06, 07, 08

**Status:** ready-for-agent

- [ ] A local dry run of the server compose comes up from nothing, applies the production blueprints, and a staff user created with `add-user.sh` signs in with the texted code
- [ ] The built image contains no dev blueprint, and the smoke check fails when a dev user is present
- [ ] Backup then restore of the `lifeplatform` and `authentik` pair brings back a working sign-in
- [ ] The admin UI answers 404 through Caddy, and the sign-in page still works
- [ ] The production guard refuses each dev default and each bad setting it names
- [ ] The guide's new sections were followed literally in the dry run
