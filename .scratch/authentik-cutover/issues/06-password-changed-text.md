# 06: "Your password changed" text

**What to build:** When a person changes or resets their **own** password, they get today's "your password was changed" text. Admin resets, temporary passwords set by the backend, and blueprint password sets send nothing.

**In Authentik:** a notification rule on the `password_set` event is sent to the event's own user through a webhook transport, with a bearer secret. Its policy excludes request paths under `/api/v3/core/users/`.

**The backend's password-changed endpoint:**
- answers **202** at once, and texts in the background
- **dedupes on the event id** (Authentik retries up to 5 times)
- stamps the message with the event's time
- answers 2xx to anything it deliberately ignores, so it isn't retried

Spec: Part 1 "Notifications", and Part 2 "Webhook endpoints". The decisions are in tickets 03, 07 and 08 of `.scratch/authentik-migration/`.

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] A person changing their own password receives the text once, in their language
- [ ] Completing a password reset sends the text once
- [ ] An administrator's reset, the backend setting a temporary password, and a blueprint password set send nothing
- [ ] The same event delivered twice texts once
- [ ] The endpoint refuses a call without the bearer secret
