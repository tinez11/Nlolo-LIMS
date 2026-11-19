# 04: Agent and customer logins in Authentik

**What to build:** Onboarding an agent, and the customer portal invite (including the automatic invite on the first premium), create or extend a login in Authentik through the shared client from ticket 03. One person keeps **one login** across workspaces:
- **Granting:** adds the group (`agent` or `customer`) to the existing login when the phone already has one.
- **Revoking:** removes only that group, deletes the person's sessions **and refresh tokens**, and leaves the login open if the other workspace remains.

Agent logins carry `party_id`, and customer logins carry `party_id` and `tenant_id`, as today.

Spec: Part 2, "Login management". The decisions are in tickets 04 and 10 of `.scratch/authentik-migration/`.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] Onboarding an agent creates a login in `agent` that signs in to the agent console
- [ ] Inviting a client to the portal creates a login in `customer`; the first-premium auto-invite works the same way
- [ ] Giving portal access to an existing agent adds `customer` to the **same** login, which then reaches both workspaces
- [ ] Closing that agent removes `agent`, ends their open sessions and refresh tokens, and the portal still signs in
- [ ] Revoking portal access from someone with no other workspace leaves them unable to sign in anywhere
- [ ] The agent and portal adapters' contract tests pass
