# Staff sign-in code (SMS OTP) — design

Date: 2026-11-14. Status: option A (SMS code) chosen by the user; rules below proposed, awaiting the user's review.

## Why

Staff consoles move money and change access. Today a staff sign-in is a password plus brute-force lockout
(5 failures). The security audit of 2026-11-06 (M5) lists "no staff 2FA". The user chose a code texted to the staff
member's phone, the channel every staff member already uses.

## Rules

- **Who:** staff only. Agents, customers and regulators sign in as today.
- **When:** every staff sign-in, after the password. No "remember this computer".
- **The code:** 6 digits, valid 5 minutes, single use. A new sign-in or "Send a new code" replaces the old one;
  at most one new code per minute and 3 texts per hour per account (the same limits password-reset codes use).
- **Wrong code:** 3 wrong codes end the attempt (back to the password page); failures also count towards the realm's
  existing lockout.
- **No phone:** a staff login with no phone number cannot sign in; the page says "No phone number is set for your
  login. Ask an administrator." Users and roles marks staff with no phone, and the admin adds it there (audited).
- **Text:** "Your Nlolo sign-in code is 123456. It expires in 5 minutes. Never share it." (English; Swahili only if
  staff logins record a language — to check while planning). Wording for the user to approve.
- **Logged:** each code sent and each failed code as Keycloak events; the code itself is never logged.

## How it works

Keycloak keeps the login; the platform keeps the phone numbers and the SMS gateway, exactly as for password resets:

1. A new Keycloak authenticator `staff-sign-in-code` (in `backend/keycloak/extensions`) runs after username and
   password in the `lifeplatform` realm's browser flow, conditional on the user holding the `staff` role.
2. It generates the code, keeps a hash of it in the authentication session, and asks the platform to text it via a
   new signed internal endpoint `POST /internal/staff-sign-in-code/sms` (same signature scheme as
   `/internal/password-reset/sms`).
3. The platform looks up `iam.staff_user.phone` by the Keycloak user id and sends through iam's `StaffTexts` port.
   No phone → it answers "no phone" and the page shows the message above.
4. The staff member types the code on a page in the login theme (same look as the reset-code page).

## Switch, dev and tests

- Setting `STAFF_SIGN_IN_CODE` (on/off). The production profile's `ProductionSettingsGuard` refuses to boot with it
  off.
- Dev: off by default, because dev SMS only reaches the allowlist (255755418369) and only 1 of 10 dev staff logins
  has a phone. Turned on to test by hand with a staff login whose phone is the allowlisted number.
- E2E: runs with it off, plus one spec that turns it on against the mock gateway and reads the code from the mock.

## Before switching it on in production

Every active staff login needs a phone number. Users and roles will list those missing.

## Testing

- Extension unit tests: code expiry, single use, three-strikes, resend gap.
- Backend: the internal endpoint refuses unsigned/stale requests; no phone → "no phone"; texts the right number.
- E2E as above.
