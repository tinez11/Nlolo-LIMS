# 01: Staff sign in through Authentik

**What to build:** A staff member (`staff.underwriter`) opens the staff console, is sent to Authentik's sign-in page with username and password on **one** page, signs in, and lands on the Dashboard with exactly the permissions they have today. This is the tracer bullet: the first full path through Authentik, the backend and the console. Keycloak stays in the dev stack for the other workspaces until ticket 02.

It covers:
- **Authentik 2026.8.5 in the dev stack:** server and worker, with memory limits of 1 GiB and 512 MiB.
- **The production blueprint's core:**
  - the sign-in flow: identification with the password on the same page, then user login
  - one public OAuth2 provider and application
  - `sub` = user UUID
  - a dedicated RSA signing key, with claims kept in the ID token
  - one scope mapping emitting `roles` (groups filtered to the four workspace names), `tenant_id`, `party_id` and `home_branch`
  - `offline_access`, no `email`
  - lifetimes of 5 minutes access and 30 minutes refresh
  - the four groups `staff`, `agent`, `customer` and `regulator`
- **The dev-only blueprint:** the 14 test users with their attributes, groups and `devpassword`, plus a `job_roles` attribute.
- **First-sign-in import from `job_roles`** (amended 2026-11-19): `AccessService.importFromToken` copies the `job_roles` claim once.
- **A one-off cleanup of the dev database:** the dev users' old `staff_user` rows, role grants and access history go, so they re-import at first sign-in.
- **The backend:** authorities from `roles`, not `realm_access.roles`; the Authentik issuer (trailing slash included); the **audience validator** (`aud` must hold the SPA's client id); the fake-token test helpers and test settings.
- **The console:** the OIDC configuration pointed at Authentik; identity read from `roles`.

Branch from `main` **after** step 0 (the issuer check, branch `token-issuer-audience-check`) is merged.

Spec: `backend/docs/superpowers/specs/2026-11-19-authentik-migration-design.md`, Parts 1-3. The decisions behind it are in tickets 05, 09 and 10 of `.scratch/authentik-migration/`.

**Blocked by:** None (can start immediately, once step 0 is merged).

**Status:** resolved (2026-11-19, branch `authentik-cutover`)

- [x] The dev stack brings Authentik up from nothing and both blueprints apply successfully
- [x] A decoded access token for `staff.underwriter` holds a UUID `sub`, `aud` = the SPA client id, `roles: ["staff"]`, and its `tenant_id` and `home_branch`
- [x] `staff.underwriter` signs in through the browser and reaches the Dashboard with today's permissions
- [x] The backend refuses a token whose `aud` lacks the client id, and one from a foreign issuer, each proven by a test that failed first
- [x] The affected backend and console tests pass

## Comments

**2026-11-19, done.** What was checked:

- **Blueprints:** both (`lifeplatform`, `lifeplatform-dev-users`) applied successfully on a fresh `authentik` database.
- **The token:** a real authorization-code + PKCE sign-in through Authentik's page gave an RS256 token with:
  - `iss` = `http://localhost:9100/application/o/lifeplatform/`
  - a UUID `sub`
  - `aud` = `lifeplatform-spa`
  - `roles` = `["staff"]`, plus `tenant_id`, `home_branch` and `job_roles` = `["UNDERWRITER"]`
  - a 5-minute lifetime, and a refresh token

  The stock profile scope also emits an unfiltered `groups` claim; the backend never reads it.
- **Sign-in in the browser:** `staff.underwriter` reached the Dashboard as Underwriter, with 14 permissions. `staff.admin` imported all six job roles and has 64 permissions. Both first-sign-in imports wrote `staff_user` under the new Authentik UUID.
- **Tests:**
  - backend: the whole `iam` package, 52 tests, including the 4 new decoder tests, 6 `SecurityConfigTest`, and 4 new `AccessExpressionsIdentityTest`
  - frontend: `src/auth` and `DraftProducts`, 46 tests
  - `tsc` clean
- **The dev database cleanup:** 9 old dev `staff_user` rows (role grants cascaded) and 13 `access_change` rows deleted.

Found while building:

- **`grant_types` must be set.** In Authentik 2026.8 an empty list means **no** grant is allowed: the authorize endpoint answered "Invalid grant_type for provider".
- **Blueprints must be mounted on the server too.** The admin API's apply runs there, and without the files it re-applies stale content while reporting success.
- **A Windows bind mount sends no file events** into the container, so after editing a blueprint, apply it through the API.
- **Applying skips a file whose content hasn't changed.** A value changed by hand in the admin is **not** put back until the file changes. Recorded for [Production deployment](10-production-deployment.md).
- **The `app` compose service is not updated.** It still sets `KEYCLOAK_ISSUER` (now ignored), so it would use the localhost default. That service is unused in dev; [Production deployment](10-production-deployment.md) handles the server's settings.
- **Vitest needs an uppercase `C:` working directory.** From Git Bash's `c:\` every file fails with "failed to find the current suite"; run it from PowerShell.
