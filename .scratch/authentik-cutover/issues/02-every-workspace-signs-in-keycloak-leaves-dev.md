# 02: Every workspace signs in through Authentik; Keycloak leaves dev

**What to build:** Agents, customers and the regulator sign in through Authentik as staff now do. One login in two groups (an agent who is also a customer) switches between workspaces by `X-Workspace`, as today. Sign-in is hardened:
- PKCE S256 is required by policy
- the public sign-in flow refuses superusers
- signing out revokes the tokens (`revokeTokensOnSignout`)
- the console signs a person out 10 hours after sign-in, using the token's `auth_time`

Keycloak is removed from the dev stack.

Spec: Parts 1 and 3, and the carry-over table (the 10-hour cap). The decisions are in tickets 05, 07, 10 and 15 of `.scratch/authentik-migration/`.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `agent.senior`, `customer.owner` and `regulator.tira` each sign in to their console
- [ ] A test login in both `agent` and `customer` reaches both workspaces, and is refused a workspace it doesn't hold
- [ ] A sign-in attempt without PKCE S256 is refused
- [ ] A superuser is refused on the public sign-in flow; the bootstrap admin still works through the admin route
- [ ] After signing out, the old refresh token no longer works
- [ ] A session older than 10 hours by `auth_time` is signed out (a console test with a controlled clock)
- [ ] The dev stack no longer contains Keycloak and every workspace still signs in
