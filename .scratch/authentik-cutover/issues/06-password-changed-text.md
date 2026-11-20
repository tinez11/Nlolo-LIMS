# 06: "Your password changed" text

**What to build:** When a person changes or resets their **own** password, they get today's "your password was changed" text. Admin resets, temporary passwords set by the backend, and blueprint password sets send nothing.

**In Authentik:** a notification rule on the `password_set` event is sent to the event's own user through a webhook transport, with a bearer secret. Its policy excludes request paths under `/api/v3/core/users/`.

**The backend's password-changed endpoint:**
- answers **202** at once, and texts in the background
- **dedupes on the event id** (Authentik retries up to 5 times)
- stamps the message with the event's time
- answers 2xx to anything it deliberately ignores, so it isn't retried

**The console's Change password** (added 2026-11-19, the user's decision after ticket 03): today it asks Keycloak for its built-in update-password action (`kc_action=UPDATE_PASSWORD` in `changePassword.ts`), which Authentik ignores, so after the cut-over the button would do nothing. Build it here, since it is the main source of the "own change" this text is for:
- an Authentik change-password flow that asks for the current password, then a new one twice, with the same rules as the forced change from ticket 03 (the password policies `lifeplatform-password-rules` and `lifeplatform-password-not-username-or-temporary`)
- the console's Change password sends the person there and brings them back to the page they were on
- keep Keycloak's default of signing out the person's other sessions, or record the difference for the user

Spec: Part 1 "Notifications", and Part 2 "Webhook endpoints". The decisions are in tickets 03, 07 and 08 of `.scratch/authentik-migration/`.

**Blocked by:** 02

**Status:** resolved (2026-11-20, branch `authentik-cutover`)

- [x] A person changing their own password receives the text once, in their language
- [x] Completing a password reset sends the text once (checked in [Password reset at /reset](07-password-reset-at-reset.md): the recovery flow is built there)
- [x] An administrator's reset, the backend setting a temporary password, and a blueprint password set send nothing
- [x] The same event delivered twice texts once
- [x] The endpoint refuses a call without the bearer secret
- [x] The console's Change password asks for the current password, applies the password rules, returns to the page it came from, and sends the text once

## Comments

**2026-11-20, done.** What was checked, live (the backend texting the mock SMS gateway, with `staff.underwriter`'s staff record given the test phone `+255700000098`):

- **The console's Change password,** from `/staff/policies`, opened Authentik's "Change your password". It asked for the current password, then the new one twice, and came back to `/staff/policies`. One text arrived: "Your Nlolo password was changed on 20 Nov at 09:27. If this wasn't you, contact us straight away." (no support number is set in dev).
- **The other device,** a second browser signed in beforehand, was signed out: after a reload it was at Authentik's sign-in, and only the current session was left. This is Keycloak's default, kept.
- **No text** for an administrator's password set through the API, nor for the temporary password set with it.
- **The forced change at the next sign-in** sent one text.
- **Restoring the dev password** through the admin API sent nothing.
- **A call without the bearer secret** answers 401.
- **Unit tests:**
  - in each person's own language, from their record
  - the same event delivered twice texts once
  - answered 202 at once, with the text sent in the background
  - a login with no tenant is accepted and ignored
  - `ProductionSettingsGuard` refuses the dev secret
- **Completing a password reset** is the recovery flow in [Password reset at /reset](07-password-reset-at-reset.md). It goes through the flow executor too, so the same rule applies, but it can only be checked there.

Built:

- **The backend:**
  - `PasswordChangedController` takes the bearer secret `PASSWORD_CHANGED_WEBHOOK_SECRET` and the body `{eventId, userId, tenantId, partyId, at}`. It answers 202, and 2xx to whatever it ignores on purpose.
  - `PasswordChangedNotices` dedupes on the event id (remembered for a day) and texts on a background thread, in the person's own tenant. The text is stamped with Authentik's time, in the language on the staff or client record. Keycloak used to send the language; the new body has none.
- **The blueprint,** for the text:
  - a notification rule on `password_set`, sent to the event's own user through a webhook transport, with body and header mappings
  - its policy passes only events raised through the flow executor (`/api/v3/flows/executor/`): the person's own change, a forced change, or a recovery. API sets (administrators, the backend) and blueprint sets, which have no request at all, never text. This matches the user's existing rule and is stricter than the spec's "exclude `/api/v3/core/users/`".
- **The blueprint,** for Change password, the `lifeplatform-change-password` flow:
  - the current password, for the signed-in person
  - the choose-password and save stages from ticket 03, with the same rules
  - signing out other devices, then a redirect back to the console
- **The console:** Change password opens `<auth origin>/if/flow/lifeplatform-change-password/?return_to=<page path>`.

Found while building:

- **The return address must be relative.** Authentik follows only a relative `next`. So the last step is a redirect stage, and a policy builds its target: `LP_APP_ORIGIN` plus the page path, accepted only if it is letters, digits and `/ - _ .`. **The query string is not carried back** (Keycloak's action returned to `?tab=…` too): after changing your password you're on the same page, but with its default tab.
- **A flow started by a signed-in person has no pending user,** so the password stage failed with "Unknown error". A policy on that step makes the signed-in person the pending user.
- **Authentik doesn't sign other devices out** on a password change. A policy after the save deletes the person's other sessions and refresh tokens. Expression policies may import Authentik's models.
- **Vite pre-bundles its dependencies** after a config change. A page loaded before that finishes hits React's "Invalid hook call"; restarting Vite clears it.
