# 09: E2E suite on Authentik

**What to build:** The Playwright suite signs in through Authentik:
- **One shared sign-in helper,** used by all the setup projects, finds fields by label: "Username", "Password", and the "Log in" button. `storageState` keeps Authentik's session cookie.
- **API-level setup** mints tokens with the password grant using each dev user's **app-password token**. The dev-only blueprint creates those tokens with a fixed dev key.
  - **The provider's `grant_types`** (found in ticket 01) lists only `authorization_code` and `refresh_token`, and an unlisted grant is refused. The password grant must be allowed for this, and only in dev: the production blueprint must not gain it.
  - **The PKCE policy** (ticket 02) only judges requests that carry the authorize endpoint's context, so the password grant at the token endpoint passes it.
- **The dev seed scripts** (added 2026-11-19, found in ticket 02): `backend/scripts/seed-lib.sh` and the demo and fixture scripts that use it still get tokens from Keycloak's password grant. Keycloak left the dev stack in ticket 02, so they fail until they mint tokens the same way as the e2e setup. Ticket 11's fresh-bootstrap rehearsal needs them.
- **New specs:**
  - the staff sign-in code end to end through the mock SMS gateway (the `e2e-sign-in-code` user)
  - a `/reset` happy path

The suite runs at `--workers=2` with the dev stack's memory limits. If the first run shows sign-in timeouts, try `--workers=1` before changing anything else.

Spec: Part 4. The decision is in ticket 14 of `.scratch/authentik-migration/`.

**Blocked by:** 02, 05, 07, 08

**Status:** ready-for-agent

- [ ] Every setup project signs in through the shared helper
- [ ] The specs that use API-level setup mint tokens with app passwords and pass
- [ ] The sign-in code spec and the reset spec pass
- [ ] Each changed spec passes on its own (the full suite runs in ticket 11)
- [ ] `seed-dev-data.sh` seeds a fresh dev database against Authentik
