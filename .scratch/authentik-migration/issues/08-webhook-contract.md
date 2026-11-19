# The webhook contract between Authentik and the backend

Type: grilling
Status: resolved
Blocked by: 07, 13
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

Authentik calls the backend to text a staff sign-in code and to say a password changed. The reset request no longer goes through Authentik: since [Swahili on the Authentik login pages](11-swahili-login-pages.md), it starts on a platform page that calls the backend directly, and that page is [Where a password reset starts](12-reset-request-page.md). What is the contract for each?

- the endpoint paths, and whether today's three controllers are kept or renamed
- the body fields
- how the backend trusts the call: what replaces `KeycloakSignature` and the `PASSWORD_RESET_SMS_SECRET` shared secret
- replay protection and timeouts
- what Authentik shows the person when the backend is down
- what `ProductionSettingsGuard` must refuse in production
- **Which number a sign-in code goes to.** [Test the silent staff phone set-up](13-test-silent-phone-setup.md) showed the authentik device keeps the number it was created with. Does the backend send to the staff record's phone and ignore `To`, or delete the device when the phone changes?

## Answer

Decided by the user, 2026-11-19. Two calls remain; `/internal/password-reset/sms` leaves this contract, because the reset starts on the platform's page.

1. **Trust: a bearer secret per endpoint.** Both calls stay on the internal Docker network, kept off the public address by Caddy's `/api/internal/*` block. `KeycloakSignature` and its HMAC headers are deleted.
   - The password-changed call is protected against replay by deduping on Authentik's event id.
   - Replaying the sign-in code call only re-texts the staff member's own phone, under the caps, so it needs nothing more.
2. **Which phone, and the device key.**
   - The flow's policy copies the login's **username** (always present, unique, already a phone number) into `prompt_data.phone`, so the Authentik device number is only a key.
   - The backend **ignores `To`** and keeps today's rule (`StaffSignInCodes.send`): the staff record's phone, falling back to the login's phone.
   - Phone changes need no device reset, and the "no phone attribute" deny case disappears from the flow.
3. **Sign-in code endpoint:** `POST /internal/staff-sign-in-code/sms`.
   - **Body:** a webhook mapping sends `{tenantId, userId (UUID), code, phone}`.
   - **Replies** (Authentik reads only the status, and shows the text for 400 and above; English-only, like the staff console):

     | Outcome | Status | What the person sees |
     |---|---|---|
     | texted | 204 | the code field |
     | no phone on the staff record or the login | 422 | "No phone number is on file for you. Ask an administrator." |
     | over the cap (5 an hour, 1 a minute) | 429 | "Too many codes. Wait a minute and try again." |
     | the gateway failed | 503 | "We couldn't send your code. Try again." |
     | backend down | (no reply) | Authentik's generic error; the person stays on the page |

   - The `OFF` reply disappears.
   - **The caps move into the backend** (`StaffSignInCodes`).
4. **Password-changed endpoint:** `POST /internal/password-changed`.
   - **Body:** `{eventId, userId (UUID), tenantId, partyId, at}`.
   - **The backend:**
     - answers 202 at once and texts in the background
     - dedupes on `eventId`
     - stamps the message with `at`
     - answers 2xx to anything it deliberately ignores, so Authentik doesn't retry it
   - **The Authentik policy** passes only the person's own change or reset.
5. **Naming:** the controllers and helpers lose "Keycloak" from their names; the paths stay.
6. **The on/off switch.** One `.env` variable, `STAFF_SIGN_IN_CODE`, is passed to both containers.
   - The blueprint reads it with `!Env` and creates the code step only when it's on.
   - The backend keeps `app.staff-sign-in-code.enabled`, so `ProductionSettingsGuard` still refuses a production start with it off.
   - The guard also refuses the dev default bearer secrets, replacing today's `password-reset.sms-secret` and `dev-secret-` entries.

