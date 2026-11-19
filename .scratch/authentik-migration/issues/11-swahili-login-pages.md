# Swahili on the Authentik login pages

Type: grilling
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Today's Keycloak login, sign-in code and reset pages are in English and Swahili. Stock authentik 2026.8.5 ships no `sw` translation, so its pages fall back to English, and text entered in the admin UI is single-language whatever is chosen. See [What does it take to run Authentik here?](06-running-authentik.md).

Which of these is acceptable?

- (a) A custom-built authentik image carrying a Swahili translation, patched and rebuilt on every upgrade.
- (b) Contribute Swahili upstream through Transifex, and run English-only until it ships.
- (c) English-only login pages for good, with Swahili kept only in the texts.
- (d) This gap is a no-go.

The answer feeds [Go or no-go on Authentik](07-go-no-go.md).

## Answer

**(e), the hybrid** (the user, 2026-11-19).

- **The reset request leaves Authentik.** A person starts a password reset on a page in the platform's own bilingual frontend. That page calls the backend, which refuses staff, applies the caps, asks Authentik for its 15-minute recovery link and texts it in the person's language.
- **Only two Authentik pages stay English:** the sign-in page and the set-new-password page. Both are short (a phone field, a password field, a button).
- **No custom image, no Swahili fork.** Authentik runs its stock image.
- **The Authentik flow webhook for the reset goes away.** It was the design in [Can Authentik send a password reset by SMS only?](02-sms-password-reset.md); the backend endpoint replaces it.
- **Contributing Swahili upstream** is a goodwill follow-up, outside this effort.

Facts this rests on: only the customer portal and the public quote link are bilingual today (`AppShell.tsx:38`); the staff and agent consoles are English-only. Today's Keycloak theme translates 140 of its 200 login strings.

What it opens: [Where a password reset starts](12-reset-request-page.md). The webhook contract loses its reset call: see [The webhook contract between Authentik and the backend](08-webhook-contract.md).
