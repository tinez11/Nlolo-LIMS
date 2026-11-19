# 04: Agent and customer logins in Authentik

**What to build:** Onboarding an agent, and the customer portal invite (including the automatic invite on the first premium), create or extend a login in Authentik through the shared client from ticket 03. One person keeps **one login** across workspaces:
- **Granting:** adds the group (`agent` or `customer`) to the existing login when the phone already has one.
- **Revoking:** removes only that group, deletes the person's sessions **and refresh tokens**, and leaves the login open if the other workspace remains.

Agent logins carry `party_id`, and customer logins carry `party_id` and `tenant_id`, as today.

Spec: Part 2, "Login management". The decisions are in tickets 04 and 10 of `.scratch/authentik-migration/`.

**Blocked by:** 03

**Status:** resolved (2026-11-19, branch `authentik-cutover`)

- [x] Onboarding an agent creates a login in `agent` that signs in to the agent console
- [x] Inviting a client to the portal creates a login in `customer`; the first-premium auto-invite works the same way
- [x] Giving portal access to an existing agent adds `customer` to the **same** login, which then reaches both workspaces
- [x] Closing that agent removes `agent`, ends their open sessions and refresh tokens, and the portal still signs in
- [x] Revoking portal access from someone with no other workspace leaves them unable to sign in anywhere
- [x] The agent and portal adapters' contract tests pass

## Comments

**2026-11-19, done.** What was checked, live through the backend's endpoints (`staff.admin`'s real token, the dev Authentik, the browser):

- **An agent login:** giving Amina Owner's agent record a login created `customer.owner@example.tz` in `agent`, carrying `party_id` and `tenant_id`. The agent, made to choose a password, reached the agent Dashboard.
- **Portal access for the same person** joined that login: there is still one login with that username, now in `agent` and `customer`. Signing in offered "Continue as Sales" and "Continue as Customer".
- **Closing the agent login** while it had 2 sessions and 2 refresh tokens left it in `customer` only, still active, with 0 sessions and 0 refresh tokens. The portal then signed in to the customer Home, and the agent workspace answered 403.
- **A customer-only invite** (Neema Paid) signed in to the portal. Revoking it closed the login: no groups, inactive, no sessions. Signing in then failed ("Failed to authenticate.").
- **Inviting her again** joined her existing, closed login and reopened it (the same UUID).
- **The first-premium auto-invite** calls the same `PortalAccessService.invite`, so it takes the same path.
- **Tests:**
  - new: `AuthentikWorkspaceLoginsTest` (10), `AuthentikAgentLoginsTest` (4), `AuthentikCustomerAccountsTest` (4), and 2 new in `AuthentikClientTest`
  - also run: the existing contract and portal integration tests, `ProductionSettingsGuardTest` and `ModularityTests`

Built:

- **`AuthentikWorkspaceLogins`** (root package), the one-login-per-person rules:
  - **Grant** creates the login, or joins the login whose `party_id` is this party. Any other login is never taken over, including the many this service can't see (staff, dev, made on the server).
  - **Revoke** removes the group, then closes the login if no workspace is left. If one is left, it closes and reopens the login, because closing is what ends sessions and refresh tokens in Authentik. That needs no right beyond changing the users this service created, rather than global session and token deletion.
  - **Restore** adds the group back and reopens the login.
- **`AuthentikAgentLogins` and `AuthentikCustomerAccounts`** replace the Keycloak adapters, which are deleted. The ids they return and take are the login's UUID, the token's `sub`. The `keycloak_user_id` columns keep their name until [Remove Keycloak](11-remove-keycloak-and-pass-the-merge-gate.md).
- **The blueprint:** the backend may view, add and remove on `agent` and `customer`.
- **Settings:** the `app.agent-logins.keycloak.*` and `app.portal.keycloak.*` blocks are gone, and so are their guard entries. The guard checks `app.authentik.api-token`.

Found while building:

- **A slow first call.** The first call after Authentik restarted took over 10 seconds, and staff saw a 500 "unexpected error". The read timeout is now 30 seconds, and no answer is a 503 refusal ("Authentik did not answer…"), pinned by a test.
- **An Authentik race.** If a login is closed while that person is mid-way through the forced password change, the flow's user-write stage saves a copy of the login loaded earlier, and the login comes back active. It's narrow (the close has to land in that moment), and found only because a test script raced it.
- **Dev data.** Login records from before the cut-over point at Keycloak logins that no longer exist, so changing them fails. Amina Owner's 7 agent-login records and 1 portal-access record were deleted for this check. Kindelema's 2 records (`+255755418369`, `martinfredy2000@gmail.com`) were left alone. Re-seeding (ticket 09) or deleting them clears it.
- **Dev SMS is off,** so a texted temporary password goes nowhere. The live check set known temporary passwords through Authentik's admin API, flagged for change like the backend's.
- **Usernames follow today's rule.** With mail on in dev, the agent's username was their email; with it off, as in production, it's the phone. Unchanged.
- **Today's portal rules still apply:** only a person who holds a policy can be invited.
