# Can Authentik tell the backend when a password changes?

Type: research
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Today a Keycloak event listener (`PasswordChangedListener`, `PasswordChangeNotifier`) calls the backend (`PasswordChangedController`, `PasswordChangedNotices`), and the backend texts the person "your password was changed". That text is what tells someone that another person reset their password.

In Authentik:

- Which event fires on a password change, by the user, through recovery, or by an admin? Can a notification rule with a **webhook transport** send it to a backend endpoint?
- What is in the webhook body (user id, username, attributes), and can it be signed or carry a shared secret so the backend can trust it?
- Is delivery synchronous, retried or best-effort? What happens if the backend is down?

Answer from primary sources for the current stable release, citing each claim.

## Findings

[research/03-password-changed-text.md](../research/03-password-changed-text.md)

## Answer

Yes, with configuration only (authentik 2026.8.5).

- **The setup:** the `password_set` event, a notification rule sent to the event's own user, a generic webhook transport and a body mapping. Together they post today's fields (tenantId, userId, partyId, language, plus an event id and time) to the backend.
- **All of it fits in blueprints,** so `PasswordChangedListener` and `PasswordChangeNotifier` are deleted.
- **Trust:** there is no built-in signing. The options:
  - a static bearer secret: documented, no replay protection
  - an HMAC computed in a header mapping: untested
  - a short-lived JWT from `ak_create_jwt_raw`, checked against authentik's public keys: which key signs it is unconfirmed

  The header mapping runs again on every retry, so timestamps and tokens stay fresh.
- **One event covers every path.** Admin resets, API password sets and blueprint password sets fire the same event as a person's own change. A policy can exclude `/api/v3/core/users/` request paths. Without it, the backend's own login creation and admin resets will text "your password was changed". Whether an admin reset *should* text the person is a decision for the go/no-go: today's Keycloak listener only hears the person's own change.
- **Delivery** is queued in Postgres and handled by a background worker, off the person's request. There are 5 retries with exponential backoff, about 8–16 minutes in total, then the notice is dropped. Any non-2xx reply is retried, including 4xx, with a 30-second timeout per attempt.
- **Backend changes needed:**
  - dedupe on the event id, because retries can duplicate texts
  - stamp the text with the event's time, not the backend's current time
  - answer 2xx to anything ignored on purpose, so it is not retried
- **Minor:** the event is written just before the password is saved, so a failed save could still send a text.

These feed [Go or no-go on Authentik](07-go-no-go.md) and [The webhook contract between Authentik and the backend](08-webhook-contract.md).
