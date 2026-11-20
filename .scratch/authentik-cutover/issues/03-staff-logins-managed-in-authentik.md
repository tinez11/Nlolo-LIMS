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

**Status:** resolved (2026-11-19, branch `authentik-cutover`)

- [x] Adding a staff user on the Users screen creates an Authentik login that signs in to the staff workspace
- [x] Adding the same phone twice answers "already exists", not an error
- [x] Closing the staff user stops their sign-in and invalidates an open session
- [x] A reset gives a temporary password that forces a new password at the next sign-in, with `tenant_id` and `home_branch` intact afterwards
- [x] The service-account token can't reset a user it didn't create, and can't add anyone to `regulator` (a test against the dev Authentik)
- [x] The adapter's contract tests pass against the new client

## Comments

**2026-11-19, done.** What was checked:

- **Live, through the backend's Users endpoints** (`staff.admin`'s real token, the dev Authentik, the browser):
  - Adding a staff user created an Authentik login in `staff`, at `lifeplatform/staff`, with `tenant_id`, `home_branch` and `reset_password: true`.
  - Adding the same username again answered 409 "A login named … already exists. Choose another."
  - At first sign-in with the temporary password, Authentik asked for a new one. It refused a 5-character password ("at least 8"), the username, and the temporary password itself, then accepted a good one and landed on the staff Dashboard. Afterwards the attributes still held `tenant_id` and `home_branch`, and `reset_password` was `"false"`.
  - A reset gave a new temporary password, with the same forced change.
  - Closing the login made it inactive. Its 2 sessions and all its refresh tokens were gone, the open console was sent back to sign-in, and the password was refused ("Failed to authenticate.").
- **The service account's rights,** against the dev Authentik with its token:
  - **Allowed:** creating a login, seeing it, adding it to `staff`, setting its password and attributes, disabling and deleting it.
  - **Refused:** resetting or disabling a login it didn't create (`staff.underwriter`, and `akadmin`), adding anyone to `regulator` or to the admins group, and removing from `staff`.
- **Tests:**
  - `AuthentikClientTest` (11) and `AuthentikStaffLoginsTest` (7), against `FakeAuthentik`, a scripted stand-in whose bodies come from the dev Authentik.
  - The `iam` package, `ProductionSettingsGuardTest` and `ModularityTests`: 78 tests in all, every one passing.

Built:

- **`AuthentikClient`** (root package, shared with tickets 04 and 05). It looks up the integer id from the UUID, maps the "username must be unique" 400 to `UsernameTaken`, merges attributes rather than replacing them, and adds or removes a login from a group by name.
- **`AuthentikStaffLogins`** replaces `KeycloakStaffLogins`, which is deleted. `sendSetPasswordLink` now refuses, since email is never a channel. The Users screen only calls it when mail is on.
- **The blueprint:**
  - the `lifeplatform-backend` service account, its role, and initial permissions on the users it creates
  - its non-expiring token, keyed from `AUTHENTIK_API_TOKEN` with no default
  - view and add-user rights on `staff` only
  - two stages, run only while `reset_password` is true: choose a password (with the password rules), then save it and clear the flag
- **Settings:** `app.authentik.base-url` and `app.authentik.api-token`. `ProductionSettingsGuard` refuses the dev token in place of the old staff admin secret. The dev compose gives Authentik the same dev token.

Found while building:

- **Authentik drops a chunked request body.** Every create answered 400 "This field is required." The client buffers each body so it goes with its length, over plain HTTP/1.1. A contract test pins this.
- **Java's `HttpURLConnection` can't send PATCH,** which Authentik uses to change a user. The client uses the JDK `HttpClient`.
- **A hidden prompt field's value is its placeholder,** never evaluated, so the flag is cleared to the string `"false"`. The check reads only an exact `true`.
- **Authentik's pages are in shadow DOM:** scripts must find text with Playwright locators, not `body.innerText`.
- **The service account can't manage logins it didn't create:** the dev users, and the first administrator made on the server. Closing or resetting one of those from the Users screen says so and changes nothing.
- **Keycloak's "not one of the last 3 passwords"** needs Authentik's enterprise unique-password policy. Kept: at least 8 characters, not the username, and not the temporary password itself. **The user accepted dropping it** (2026-11-19, recorded in the spec's carry-over table).
- **The console's own Change password** asks Keycloak for its built-in update-password action, which Authentik ignores. **The user put it in [Password changed text](06-password-changed-text.md)** (2026-11-19).
