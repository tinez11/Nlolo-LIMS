# Can Authentik send a password reset by SMS only?

Type: research
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Today a Keycloak extension (`ResetDeliveryAuthenticator`, `ResetCodeTexter`, `ResetCodes`) replaces email delivery of the reset: the backend (`PasswordResetSmsController`, `PasswordResetTexts`) texts the person a link that works for 15 minutes, in English or Swahili. Staff cannot reset their own password; only agents and customers can. Email is never a channel.

Can Authentik's recovery flow do the same?

- Can a recovery flow deliver its link or code by SMS (the generic SMS provider, or a webhook from a stage or policy) with **no email stage at all**?
- Can the link's lifetime be set to 15 minutes?
- Can the flow refuse staff (by group) while allowing agents and customers?
- Which identifier does the person type to start recovery? The username is the phone number.
- Can the backend learn the person's language for the text? It knows each client's language itself; does the request carry the user or username so the backend can look it up?

Answer from primary sources for the current stable release, citing each claim.

## Findings

[research/02-sms-password-reset.md](../research/02-sms-password-reset.md)

## Answer

Possible with configuration plus backend code, and no authentik extension (authentik 2026.8.5). Delivery changes from a code to a link.

- **The question's premise was wrong.** Today the person gets a **six-digit code** that lasts 15 minutes and is typed into the reset page (`ResetCodes.java`, `PasswordResetTexts.java`), not a link. The link existed only by email, which is off.
- **No native SMS recovery:**
  - The Email stage sends email only.
  - The SMS stage texts only to a phone set up as a sign-in device. Its code lasts a fixed 5 minutes, and it behaves differently when the number has no account.
- **The workable design:**
  - **Request flow:** the person types their phone number, which is the username. Unknown numbers carry on as if real. A rule in the flow posts the number, signed, to a backend webhook, and the page says "if this number has an account, we texted a link".
  - **Backend:** refuses staff and applies the hourly cap. It asks authentik's documented recovery-link API for a link lasting 15 minutes (`POST /core/users/{id}/recovery/`, `token_duration=minutes=15`) and texts it in English or Swahili. The link works once.
  - **Set-password flow:** a second "not staff" check.
- **Gaps:**
  - **Code becomes link:** a visible change to people's experience, and the SMS wording changes. This is the user's call at the go/no-go.
  - **The link's host name** comes from however the backend calls authentik, so the backend must use the public address or rebuild the link.
  - **The "we texted you" page** is an error-style screen whose look hasn't been seen. That belongs to [Login look in Authentik](09-login-look.md).
  - **Phone numbers** must match the username exactly, with no normalising of `0755…` against `255755…`.
  - **The backend needs an authentik API token** with reset permission.
- **Unconfirmed:** that the flow rule can import Python modules. The source code suggests it; the docs don't say.

These feed [Go or no-go on Authentik](07-go-no-go.md) and [The webhook contract between Authentik and the backend](08-webhook-contract.md).
