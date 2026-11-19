# Login look in Authentik

Type: prototype
Status: resolved
Blocked by: 06, 07, 13
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

What do Authentik's sign-in, sign-in code and reset pages look like with the platform's branding? Use the green palette and brand photo from `backend/keycloak/themes/lifeplatform/login`, which deliberately looks different from the console, in English and Swahili.

Build a rough blueprint and CSS prototype in a local Authentik. Show it beside today's Keycloak page, and decide which differences are acceptable.

## Answer

The user's decisions, 2026-11-19, after comparing today's Keycloak page with three authentik 2026.8.5 variants at desktop and phone width:

1. **Variant A, "Split leaf".** The `content_right` flow layout, the brand photo as the flow background, and a white form panel styled through `::part(main)`. Accepted with its known limits:
   - The panel can't be pinned flush right or full height.
   - authentik crops the photo itself.
   - **On a phone the photo disappears**, leaving a plain white page with the mark and the form.
2. **A new version of `brand.jpg` without the NLOLO wordmark,** with our N-leaf mark (a sized `mark.svg`) carrying the brand above the form. This asset is needed in the build.
3. **These differences are accepted:**
   - bold labels and red required-field stars, which sit in a component the brand CSS doesn't reach
   - the system font instead of Inter
   - authentik's language switcher
   - no "Accounts are issued by your administrator" line

What carries over, in `looks/base.css` and `looks/a-split.css`:
- the green focus ring
- the 48px rounded fields
- the lime pill button
- the "Welcome back." title
- **username and password on one page,** by setting the identification stage's `password_stage`. Remove the separate password stage binding in the real flow.
- the footer ("Powered by authentik") hidden

Still needed, from [Where a password reset starts](12-reset-request-page.md): a "Forgot your password?" link from this page to the platform's reset page.

**The prototype** is on the throwaway branch `prototype/authentik-login-look` (`bac90e2d`), never to be merged: `compare.html`, the screenshots, `look.mjs`, `looks/*.css` and the compose file. It's also in the working tree at [spike/](../spike/), with the comparison at [spike/compare.html](../spike/compare.html).
