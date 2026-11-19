# Where a password reset starts

Type: grilling
Status: resolved
Blocked by: 07, 13
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

[Swahili on the Authentik login pages](11-swahili-login-pages.md) moved the start of a password reset out of Authentik and into the platform's frontend. [Go or no-go on Authentik](07-go-no-go.md) then kept the reset a typed code: the backend texts a six-digit code, checks it on this page, and only then sends the browser to Authentik's recovery link. What exactly is that page?

- **Where it lives:** customers use the bilingual portal, but where does an agent start, given the agent console is English-only? One shared page for both, or one per console?
- **How people reach it:** Authentik's sign-in page needs its "Forgot password?" link pointed at this page, not at Authentik's own recovery flow. Can it, and how does the page know which console the person came from?
- **What it says:**
  - wording that never reveals whether a number has a login
  - how a typed number is normalised (`0755…` against `255755…`) before it is matched to a username
  - what the person sees when the hourly cap is hit
- **Protecting it:** today's public quote link uses Turnstile and a visit token. Does this page reuse that protection?
- **Where the person lands after setting a new password:** the console or portal they started from?

## Answer

The user's decisions, 2026-11-19. Everything reuses the public quote link's machinery: `PublicAccess` (configured insurer tenant, Turnstile, visit token), `PhoneCodes` (codes kept as hashes), `PhoneNumbers.normalise` and the bilingual `/quote` route.

1. **One public page, `/reset`,** outside every console, shared by agents and customers.
   - It has the English/Kiswahili switch, so agents get Swahili here too.
   - Staff numbers get the same neutral answer, and nothing is texted to them.
2. **Reaching it, and landing afterwards.**
   - The brand's recovery flow is a one-step Authentik flow that redirects to `/reset`. Whether the 2026.8.5 redirect stage can point at an outside URL is a build-time check; the fallback is a link in the flow's title text.
   - After the new password is set, the person lands on the platform root `/`, and universal login takes them on. Nothing remembers the console they came from.
3. **Behaviour:**
   1. The typed number is normalised with `PhoneNumbers.normalise`, behind Turnstile and the visit token.
   2. The page always says "If this number has a login, we've texted a code," revealing neither existence nor staff status.
   3. Today's six-digit code (15 minutes, five wrong tries spend it, as `ResetCodes` does) is typed on the same page.
   4. Once the code checks out, the backend fetches Authentik's recovery link (`POST /core/users/{id}/recovery/`) and sends the browser to it. The link is never texted.
   5. Over the hourly cap, the page says "Too many codes for this number. Try again in an hour." Unknown numbers hit the same cap.
4. **Tenant:** the deployment's configured insurer, never read from the request, as with the public quote.
