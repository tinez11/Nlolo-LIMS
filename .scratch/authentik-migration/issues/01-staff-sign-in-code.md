# Can Authentik text staff a sign-in code on every sign-in?

Type: research
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Today a Keycloak extension (`StaffSignInCodeAuthenticator`, `StaffCodeTexter`, `SignInCodes`) asks the backend (`StaffSignInCodeController`) to text a code after the password at every staff sign-in. Only staff get it, not agents or customers. It is off in dev and required in production.

Can Authentik do the same, natively?

- Can the SMS authenticator stage with the **generic** provider (webhook) send the code to a backend endpoint instead of Twilio? What exactly is the request: method, body fields, phone and code, any signing or authentication?
- Can a flow require the code only for staff (by group or a policy) and skip it for agents and customers on the same login page?
- Can the phone come from a user attribute set when the login is created, with no enrolment screen for the user? Or must each user enrol a device first?
- Can it be switched off by configuration for dev and tests?

Answer from primary sources (goauthentik.io docs, the authentik source on GitHub) for the current stable release, citing each claim.

## Findings

[research/01-staff-sign-in-code.md](../research/01-staff-sign-in-code.md)

## Answer

Yes, with configuration only (authentik 2026.8.5).

- **Sending:** the generic SMS provider posts JSON to a backend address. A webhook mapping can shape the body as `{tenantId, userId, code, phone}`.
- **Trust:** only a bearer token or a username and password. There is no signed request, so today's signed-request check (`KeycloakSignature`) becomes a shared bearer secret.
- **Staff only:** a group or policy on the code stage limits the code to staff. It is asked at every sign-in by default.
- **Phone, a workaround:** the stored phone can't be set ahead of time through the API or blueprints. Instead it is set up silently at the first sign-in, by a policy that copies the login's `phone` attribute into the flow. The source code supports this but the docs don't, so it needs a test. After setup the stored phone never changes, so the backend should keep choosing the number from the staff record.
- **Dev off:** a blueprint creates the code step only when an environment variable says so. The backend can no longer answer `OFF`, because authentik reads only the status code. `ProductionSettingsGuard` must check the new setting.
- **Behaviour changes:**
  - Today three wrong codes end the attempt; authentik only slows down retries.
  - authentik has no limit on how often it sends texts, so the five-an-hour and one-a-minute caps must be enforced by the backend.
  - It is unconfirmed whether a wrong code counts towards locking the account.

These feed [Go or no-go on Authentik](07-go-no-go.md) and [The webhook contract between Authentik and the backend](08-webhook-contract.md).
