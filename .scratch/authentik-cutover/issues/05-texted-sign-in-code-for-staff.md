# 05: Texted sign-in code for staff

**What to build:** Every staff sign-in asks for a code texted to the staff member's phone, as today. Agents and customers are never asked.

**In the sign-in flow:**
- An authenticator-validation step, created only when `STAFF_SIGN_IN_CODE` is on.
- A policy passes staff only, and copies the login's **username** into `prompt_data.phone`. The first sign-in then sets up the SMS device silently, with no phone screen; the device number is only a key. This was proven in ticket 13 of `.scratch/authentik-migration/`.

**The backend's sign-in code endpoint:**
- secured by its own bearer secret
- ignores the `phone` it receives and texts the **staff record's** phone, falling back to the login's
- enforces the caps in `StaffSignInCodes` (5 an hour, 1 a minute)
- replies 204 when texted; 422 for no phone; 429 over the cap; 503 when the gateway fails, each with the message from the spec

The `OFF` reply is gone. `ProductionSettingsGuard` refuses `STAFF_SIGN_IN_CODE` off.

**For e2e:** the dev blueprint's `e2e-sign-in-code` group gets the code step even with the switch off.

Spec: Part 1 "Sign-in flow", and Part 2 "Webhook endpoints". The decisions are in tickets 01, 07, 08 and 13.

**Blocked by:** 02

**Status:** resolved (2026-11-19, branch `authentik-cutover`)

- [x] A member of `e2e-sign-in-code` signs in, receives a code at the mock SMS gateway, types it and reaches the Dashboard, with no phone screen at first sign-in
- [x] Changing the staff record's phone sends the next code to the new number, with no device reset
- [x] A staff member with no phone sees "No phone number is on file for you. Ask an administrator." and doesn't get in
- [x] A sixth code within the hour shows "Too many codes. Wait a minute and try again."
- [x] Agents and customers sign in with no code step
- [x] The endpoint refuses a call without the bearer secret
- [x] The production guard refuses a start with the switch off, and with the dev default secret

## Comments

**2026-11-19, done.** What was checked, live. The dev stack had `STAFF_SIGN_IN_CODE` off, so only the `e2e-sign-in-code` group was asked. The backend's texts went to the mock SMS gateway, allowed only to the two test numbers.

- **First sign-in of `e2e.sign-in-code`:** after the password came the code field, with no phone screen. The text went to the login's phone (`+255700000099`), the code from the mock gateway was typed, and the staff Dashboard opened.
- **After an administrator set the staff record's phone** to `+255700000098` on the Users screen, the next code went there, with no device reset.
- **A second sign-in within a minute** ended at "Request has been denied. Too many codes. Wait a minute and try again."
- **`staff.underwriter`,** added to the group for the test with no phone on the staff record or the login, ended at "No phone number is on file for you. Ask an administrator."
- **`agent.senior` and `customer.owner`** went straight to their consoles.
- **A call without the bearer secret** answers 401.
- **"A sixth code within the hour":** the 5-an-hour cap is pinned by a unit test with a controlled clock. Live, the 1-a-minute cap showed the same message; six sign-ins live would take six minutes.
- **Tests:**
  - `StaffSignInCodesTest` (10): the caps, and that asking whether a code can be sent never counts as a send
  - `StaffSignInCodeControllerTest` (5)
  - `InternalWebhookBearerTest` (2)
  - `ProductionSettingsGuardTest`: refuses the switch off, and the dev secret or none
  - the `omnichannel` and `iam` packages and `ModularityTests`, all passing

Built:

- **The backend:**
  - `StaffSignInCodes` loses the `OFF` answer. Whether to ask for a code is Authentik's flow now.
  - The caps (5 an hour, 1 a minute, per person) are held in memory, like the public quote's.
  - A login phone is used only when it is a phone number.
  - The endpoint takes a bearer secret, `SIGN_IN_CODE_WEBHOOK_SECRET`, and answers 204, or 422, 429 or 503 as plain text with the spec's words.
  - `KeycloakSignature` is no longer used here (the reset and password-changed endpoints still use it, until tickets 06 and 07).
- **The blueprint:**
  - a webhook mapping sending `{tenantId, userId, code, phone}`, where `phone` is the login's `phone` attribute
  - the generic SMS set-up stage, posting to `LP_BACKEND_URL` with the secret
  - the validation stage at order 55
  - a policy that asks staff while the switch is on, and the `e2e-sign-in-code` group always. It puts the username in the prompt data as the device key.
- **The dev blueprint:** the `e2e-sign-in-code` group, and the `e2e.sign-in-code` login (staff, Underwriter, phone `+255700000099`).
- **The dev compose** passes `STAFF_SIGN_IN_CODE`, `LP_BACKEND_URL` and `SIGN_IN_CODE_WEBHOOK_SECRET` to Authentik.

Found while building:

- **Spring Security read Authentik's bearer secret as a sign-in token.** It refused every code request with 401 before the endpoint saw it. The bearer resolver now skips `/internal/`. That also matters for the password-changed webhook in ticket 06.
- **Authentik doesn't show a refused send** (its error is attached to a field the code page doesn't display):
  - Over the cap, the page still says "A one-time use code has been sent".
  - With no phone, a first sign-in falls back to Authentik's own phone-number screen.

  **The user chose (2026-11-19)** to check first: a policy asks the backend's new `POST /internal/staff-sign-in-code/check` (same secret), and a 422 or 429 ends the sign-in at a deny stage, at order 54, with the backend's words. If the backend can't be asked, the code step goes ahead and the send itself decides. The policy repeats the "who is asked" rule, so agents and customers never reach the backend call.
- **The spec's "phone"** was the device number, which is only the username (a key). The mapping sends the login's `phone` attribute instead, which the first administrator needs before a staff record exists. Recorded in the spec.
- **The deny and code pages** are Authentik's stock look until ticket 08.
