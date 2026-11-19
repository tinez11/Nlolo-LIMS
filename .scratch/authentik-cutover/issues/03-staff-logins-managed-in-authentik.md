# 03: Staff logins managed in Authentik

**What to build:** An administrator adds, closes and resets staff logins from the Users screen as today, now through Authentik's API.

**Prefactor first:** one shared Authentik API client, which tickets 04 and 05 reuse. It covers token authentication, looking up the integer user id from the platform's UUID, and mapping Authentik's **400 with a `username` field error** to "already exists".

The backend uses a **non-expiring service-account token** whose role holds only:
- "add user", globally
- change, delete and reset-password on users it created
- add-only on the `staff` group
- nothing on `regulator`

**Behaviour:**
- **A new staff login** is created with `tenant_id` and `home_branch`, in `staff`.
- **Closing a staff login** disables it, which also ends its sessions and tokens.
- **A temporary password** is set, then the `reset_password` attribute. A check stage in the sign-in flow forces a change at first sign-in. Save the whole attributes record, or `tenant_id` and `party_id` are lost.

Spec: Part 2, "Login management". The decisions are in tickets 04 and 10 of `.scratch/authentik-migration/`.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] Adding a staff user on the Users screen creates an Authentik login that signs in to the staff workspace
- [ ] Adding the same phone twice answers "already exists", not an error
- [ ] Closing the staff user stops their sign-in and invalidates an open session
- [ ] A reset gives a temporary password that forces a new password at the next sign-in, with `tenant_id` and `home_branch` intact afterwards
- [ ] The service-account token can't reset a user it didn't create, and can't add anyone to `regulator` (a test against the dev Authentik)
- [ ] The adapter's contract tests pass against the new client
