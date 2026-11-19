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

**Status:** ready-for-agent

- [ ] A person changing their own password receives the text once, in their language
- [ ] Completing a password reset sends the text once
- [ ] An administrator's reset, the backend setting a temporary password, and a blueprint password set send nothing
- [ ] The same event delivered twice texts once
- [ ] The endpoint refuses a call without the bearer secret
- [ ] The console's Change password asks for the current password, applies the password rules, returns to the page it came from, and sends the text once
