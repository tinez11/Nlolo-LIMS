# How workspaces and job roles map to Authentik groups

Type: grilling
Status: resolved
Blocked by: 05, 07, 13
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Today a login carries Keycloak realm roles. Some are workspace markers, checked by `Workspace.isWorkspaceRole`; the rest are job roles such as UNDERWRITER and FINANCE_APPROVER. The platform's own `iam` permissions sit on top.

In Authentik, what is the group model?

- one group per workspace plus one per job role, or nested groups
- how the `roles` claim is built from those groups
- how a person who is both staff and customer is represented
- which groups the backend's service account is allowed to assign

## Answer

The user's decisions, 2026-11-19.

What today's code shows: logins carry **only workspace roles**. `KeycloakStaffLogins`: "No job roles are written to Keycloak: the platform holds them." Job roles come from the platform's `iam` tables via `/access/me` (`app.access.source=platform`). The seven job-role realm roles serve only dev test users and the `token` access source, whose tests build fake tokens.

1. **Four Authentik groups, nothing else:** `staff`, `agent`, `customer`, `regulator`, named exactly as today's workspace roles, so `Workspace.resolve` and the frontend's `workspacesOf` read the same names.
   - **Job roles stay entirely in the platform.** The job-role realm roles are not recreated.
   - **Dev test users** get their job roles from the `iam` seed.
2. **The `roles` claim** is built from `user.all_groups()`, **filtered to those four names**, as a flat list of strings (`["staff"]`, `["agent", "customer"]`). Authentik's own groups never reach the token.
3. **One login per person, in several groups,** as today, with the workspace chosen per request by `X-Workspace`.
   - Portal access for an existing agent adds `customer` to the same login.
   - Closing an agent removes `agent` and leaves the login open if `customer` remains.
**Amended 2026-11-19 (the user, during cut-over ticket 01):** "job roles never in Authentik" missed that `AccessService.importFromToken` copies a new staff member's token job roles into `iam` **once**, at first sign-in. That's how the dev users and a fresh server's first administrator (`add-user.sh`) get their roles. So job roles live on the Authentik login as a **`job_roles` attribute** (not groups), emitted as a separate `job_roles` claim and read **only** by the first-sign-in import. Runtime gating stays on platform permissions, and the `roles` claim stays the four workspaces. The existing dev database gets a one-off cleanup of the dev users' old `staff_user` rows; they re-import at first sign-in.

4. **The service account's rights are set per group:**
   - `agent` and `customer`: add and remove
   - `staff`: add only, at creation; a staff login is closed by disabling it
   - `regulator`: never. Regulator logins are made by the operator in Authentik's admin, so a leaked backend token can't make anyone a regulator.
