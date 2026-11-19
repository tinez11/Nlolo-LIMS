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
- **The dev-only blueprint:** the 14 test users with their attributes, groups and `devpassword`.
- **The backend:** authorities from `roles`, not `realm_access.roles`; the Authentik issuer (trailing slash included); the **audience validator** (`aud` must hold the SPA's client id); the fake-token test helpers and test settings.
- **The console:** the OIDC configuration pointed at Authentik; identity read from `roles`.

Branch from `main` **after** step 0 (the issuer check, branch `token-issuer-audience-check`) is merged.

Spec: `backend/docs/superpowers/specs/2026-11-19-authentik-migration-design.md`, Parts 1-3. The decisions behind it are in tickets 05, 09 and 10 of `.scratch/authentik-migration/`.

**Blocked by:** None (can start immediately, once step 0 is merged).

**Status:** ready-for-agent

- [ ] The dev stack brings Authentik up from nothing and both blueprints apply successfully
- [ ] A decoded access token for `staff.underwriter` holds a UUID `sub`, `aud` = the SPA client id, `roles: ["staff"]`, and its `tenant_id` and `home_branch`
- [ ] `staff.underwriter` signs in through the browser and reaches the Dashboard with today's permissions
- [ ] The backend refuses a token whose `aud` lacks the client id, and one from a foreign issuer, each proven by a test that failed first
- [ ] The affected backend and console tests pass
