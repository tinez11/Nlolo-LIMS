# What does Authentik's API offer for the backend's login operations?

Type: research
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

The backend creates and manages logins through Keycloak's admin API, as the `lifeplatform-admin` service account (manage-users and view-users only), in three adapters:

- `iam/infrastructure/KeycloakStaffLogins.java`
- `distribution/infrastructure/KeycloakAgentLogins.java`
- `omnichannel/infrastructure/KeycloakCustomerAccounts.java`

First, list every operation those adapters perform, read from the code. Expect: create a user, set attributes such as `tenant_id` and `party_id`, assign roles, send a set-your-password link, reset, close or disable, look up, and anything else.

Then, for each operation, find the Authentik API equivalent:

- the endpoint and the fields it needs
- how a non-superuser service account or API token is limited to just those rights
- how a "choose your password" link is generated for a new user (recovery link or invitation)
- whether usernames can be phone numbers

Mark any operation with no clean equivalent.

Answer from the repo's code and from Authentik's primary sources (API schema, docs, source) for the current stable release, citing each claim.

## Findings

[research/04-admin-api-for-logins.md](../research/04-admin-api-for-logins.md)

## Answer

None of the 14 operations (K0–K13 in the findings) is a hard gap (authentik 2026.8.5).

- **Clean:**
  - **Access:** a non-expiring API token owned by a service account (`expiring:false`; an expiring token gets a new key and silently breaks the backend).
  - **Create:** one `POST /core/users/` with `tenant_id`, `party_id`, `home_branch` and the workspace group.
  - **Look up** by username or by `party_id`.
  - **Grant or revoke a workspace** through the group's `add_user` and `remove_user`.
  - **Delete.**
  - **Disable** with `PATCH {is_active}`, which also ends the user's sessions and tokens.
  - **Usernames** like `+255…` are valid.
- **Workarounds:**
  - **Duplicate username:** authentik answers 400 with a field error, not 409, so the adapters must map it.
  - **Temporary password:** no native flag. Set the password, then set a `reset_password` attribute, with a check stage in the sign-in flow that forces the change. Save the whole attributes record, or `tenant_id` and `party_id` are lost. This also fires `password_set`, so [Can Authentik tell the backend when a password changes?](03-password-changed-text.md) must tell the two cases apart.
  - **Set-your-password link:** `POST /core/users/{id}/recovery/` uses the brand's single recovery flow, the same one the SMS reset uses. It needs the public `Host` header. Unused today, because email is off.
  - **Sign out a user whose login stays open** (an agent or customer workspace closed): deleting sessions needs a global permission, and refresh tokens survive it and must be deleted separately.
- **Narrow backend rights are possible:** "add user" globally, plus change, delete and reset-password only on users the backend created, plus permissions on the workspace groups. Never a global reset-password permission, or a leaked token could reset the admin's password.
- **The user id changes type.** API calls take an integer id, but the staff table keys on the token's UUID `sub`. The provider must issue the user's UUID as `sub`, and the adapters look up the integer id for each call.
- **Unconfirmed:**
  - whether a token refresh recalculates the claims
  - the permission names for deleting refresh tokens
  - whether the recovery link honours a redirect back to the console
  - what context the `password_set` event carries
