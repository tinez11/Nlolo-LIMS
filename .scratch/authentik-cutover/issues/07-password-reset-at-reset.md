# 07: Password reset at /reset

**What to build:** An agent or customer who forgot their password clicks "Forgot your password?" on Authentik's sign-in page. Authentik's one-step recovery flow redirects them to the platform's public **`/reset`** page, which is in English and Kiswahili and protected by Turnstile and a visit token, like the public quote.

1. **They type their phone number,** normalised as the public quote does. The page always answers "If this number has a login, we've texted a code."
2. **The six-digit code** lasts 15 minutes; five wrong tries spend it; there's an hourly cap, the same for unknown numbers. These are the `ResetCodes` rules moved out of the Keycloak extension.
3. **They type the code on the same page.**
4. **The backend asks Authentik for a 15-minute recovery link,** built with the **public** host, and the browser follows it to set a new password.
5. **They land on `/`,** and universal login takes them on.

Staff numbers get the same answer and are texted nothing. The tenant is the deployment's configured insurer.

**Check early:** can the 2026.8.5 redirect stage point at an outside URL? If not, the fallback is a link in the recovery flow's title text, and the user should be told.

Spec: Part 2 "Password reset", Part 3 `/reset`, Part 1 "Recovery". The decisions are in tickets 07, 11 and 12 of `.scratch/authentik-migration/`.

**Blocked by:** 02

**Status:** resolved (2026-11-20, branch `authentik-cutover`)

- [x] From Authentik's sign-in page, "Forgot your password?" reaches `/reset`
- [x] A customer resets their password end to end in English, and an agent in Kiswahili, then signs in with the new password
- [x] An unknown number and a staff number get the same answer as a real one, and nothing is texted
- [x] Five wrong codes spend the code; the hourly cap shows "Too many codes for this number. Try again in an hour."
- [x] The recovery link never appears in a text and expires after 15 minutes
- [x] The page refuses a request without a valid Turnstile token and visit token
- [x] Completing a reset sends the "your password was changed" text once (moved here from ticket 06, whose notification rule texts any password set through the flow executor)

## Comments

**2026-11-20, done.** What was checked, live. The backend ran with mail off, as in production, so new usernames are phones; texts went to the mock SMS gateway, allowed only to the test numbers.

- **From the sign-in page,** "Forgot username or password?" (Authentik's stock wording) reached `http://localhost:5173/reset`.
- **A customer in English** (Neema Paid, `+255713000101`; her portal access re-invited first):
  - Typing `0713 000 101` showed "If this number has a login, we've texted a code." The text carried the code only: "Your password reset code is … It works for 15 minutes. Do not share it…".
  - Typing the code went to Authentik's "Choose a new password" (`/if/flow/lifeplatform-set-password/?flow_token=…`) and then to `/`.
  - A fresh sign-in with the new password reached the customer Home.
  - One "your password was changed" text followed.
- **An agent in Kiswahili** (E2E Microfinance 26006815, given a login with the username `+255712000111`): the page and the text were in Kiswahili ("Namba yako ya kubadilisha nenosiri ni …"). The new password then signed in to the agent Dashboard, and one "password changed" text followed.
- **An unknown number, and a staff login's number** (one made for the test with the username `+255700000097`): both answered 204, the same as a real number, and nothing was texted.
- **Five wrong codes:** "That code is not right. 4 tries left." … "1 try left.", then "That code is not right, and there are no tries left. Ask for a new code."
- **The caps:** a second request at once answered 429 "Wait a minute before asking for a new code." With a fresh visit, the fourth in the hour answered 429 "Too many codes for this number. Try again in an hour." (Kiswahili: "Umeomba namba nyingi mno kwa namba hii. Jaribu tena baada ya saa moja."). Within one visit, the visit's own limit (5 an hour) answers first, with the general "Too many requests".
- **No visit:** 401.
- **The recovery link** expires 15 minutes after it's made (Authentik's token: 08:04:02 for a link made at 07:49:02). It's used once, and is never in a text.
- **Turnstile:** with Turnstile on, a missing token is refused before Cloudflare is asked (`PublicAccessTest`). A call with no visit is refused (`PasswordResetControllerTest`, and live above).
- **Tests:**
  - `PasswordResetsTest` (9)
  - `AuthentikResetLoginsTest` (6)
  - `PasswordResetControllerTest` (4)
  - `PublicAccessTest`, plus one test: visits work with the quote link off
  - one new test in `AuthentikClientTest`
  - the console's `PasswordResetPage.test.tsx` (3): the flow, Kiswahili, and a refusal

Built:

- **The backend:**
  - `PasswordResets`: the ResetCodes rules (six digits, 15 minutes, 5 wrong tries, 3 an hour and 1 a minute per number, held in memory). An unknown number gets a code too that is never sent, so a guess answers exactly as for a real number. The code goes through the existing `PasswordResetTexts`, to the phone on the client record, logged masked.
  - `ResetLogins`, the port, and `AuthentikResetLogins`: an open login whose username is the phone, in `agent` or `customer` and not in `staff`, with a `party_id`. The 15-minute recovery link is put on Authentik's public address (`AUTHENTIK_PUBLIC_URL`).
  - `PasswordResetController`: `POST /public/password-reset` and `/check`, behind a visit, in the configured insurer's tenant.
  - `PublicAccess.insurer()`: visits and the reset work with the quote link switched off. The quote's own calls still check its switch.
- **The blueprint:**
  - "Forgot your password?" is the `lifeplatform-forgot-password` flow, one redirect stage to `${LP_APP_ORIGIN}/reset`, set as the identification stage's `recovery_flow`.
  - The brand's recovery flow is `lifeplatform-set-password`: choose a password (ticket 03's rules), save, then go to `${LP_APP_ORIGIN}/`.
- **The console:** the public `/reset` page in English and Kiswahili, behind Turnstile, as its own chunk.

Found while building:

- **The early check passed:** a redirect stage can send the browser to an outside address, so the fallback (a link in the title) wasn't needed.
- **Two recovery flows are needed.** The sign-in page's link comes from the identification stage's `recovery_flow`. The API's recovery links open the brand's `flow_recovery`.
- **A partial blueprint entry for an existing stage fails** validation ("at least one source must be selected"). Objects that other entries refer to must be declared earlier in the file.
- **The link is built with the host the backend called Authentik on;** the backend puts the public origin on it.
- **Logins with an email as username** (dev with mail on) can't be found by phone. Production has mail off, so usernames are phones.
- **The service account only sees logins it created,** so the dev test logins (`agent.senior`, `customer.owner`) can't be reset from `/reset`.
- **Dev passwords changed in the live run:** Neema Paid's is now `Reset-cust-2026`, and E2E Microfinance 26006815's agent login is `Reset-agent-2026`.
- **Vitest's run makes the next Vite start re-bundle its dependencies,** and a page loaded meanwhile fails with "Invalid hook call". Restarting Vite after the bundle clears it.
