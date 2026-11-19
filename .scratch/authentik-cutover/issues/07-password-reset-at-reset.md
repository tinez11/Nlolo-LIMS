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

**Status:** ready-for-agent

- [ ] From Authentik's sign-in page, "Forgot your password?" reaches `/reset`
- [ ] A customer resets their password end to end in English, and an agent in Kiswahili, then signs in with the new password
- [ ] An unknown number and a staff number get the same answer as a real one, and nothing is texted
- [ ] Five wrong codes spend the code; the hourly cap shows "Too many codes for this number. Try again in an hour."
- [ ] The recovery link never appears in a text and expires after 15 minutes
- [ ] The page refuses a request without a valid Turnstile token and visit token
