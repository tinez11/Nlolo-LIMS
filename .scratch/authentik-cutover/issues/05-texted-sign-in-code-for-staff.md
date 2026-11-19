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

**Status:** ready-for-agent

- [ ] A member of `e2e-sign-in-code` signs in, receives a code at the mock SMS gateway, types it and reaches the Dashboard, with no phone screen at first sign-in
- [ ] Changing the staff record's phone sends the next code to the new number, with no device reset
- [ ] A staff member with no phone sees "No phone number is on file for you. Ask an administrator." and doesn't get in
- [ ] A sixth code within the hour shows "Too many codes. Wait a minute and try again."
- [ ] Agents and customers sign in with no code step
- [ ] The endpoint refuses a call without the bearer secret
- [ ] The production guard refuses a start with the switch off, and with the dev default secret
