# Write and approve the migration spec

Type: task
Status: resolved
Blocked by: 14, 15, 16
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Assemble the decisions on the map into one migration spec, ready for `/to-tickets`. It lives in `backend/docs/superpowers/specs/`, the repo's convention for specs.

Each decision is cited from its ticket, not re-argued, and the spec covers:

- the behaviours carried over
- the accepted changes
- the contract
- the look
- the groups
- the reset page
- e2e
- deployment
- the build order
- the open build-time checks: the redirect stage to an outside URL, the PKCE policy, `password_stage` without a duplicate password binding, and the healthcheck command

The task is HITL at the end: the user reads and approves it. Approval reaches the map's destination.

## Answer

Written as [backend/docs/superpowers/specs/2026-11-19-authentik-migration-design.md](../../../backend/docs/superpowers/specs/2026-11-19-authentik-migration-design.md). **Approved by the user, 2026-11-19.**
