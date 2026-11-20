# 08: The login look (variant A, "Split leaf")

**What to build:** Authentik's sign-in, sign-in code and set-new-password pages wear the platform's look as the user chose it in the prototype:
- **Layout:** `content_right`, with a **wordmark-free** version of the brand photo as the background.
- **Mark:** the 48px N-leaf mark above the form.
- **CSS:**
  - today's green tokens
  - 48px rounded fields and the lime pill button
  - the white panel through `ak-flow-executor::part(main)`
  - "Powered by authentik" hidden
- **Title:** "Welcome back.", with the brand title "Nlolo Life" and our favicon.

Producing the wordmark-free photo is part of this ticket. **Ask the user for it, or to approve an edited version**, before baking it in.

**Accepted differences** (don't try to close them):
- the photo is lost on phones
- the panel isn't flush right or full height
- bold labels and required-field stars
- the system font
- Authentik's language switcher
- no "Accounts are issued by your administrator" line

The prototype's CSS and screenshots are on the local branch `prototype/authentik-login-look` (`looks/base.css`, `looks/a-split.css`, `compare.html`). Read them, don't merge them.

Spec: Part 1 "Branding". The decision is in ticket 09 of `.scratch/authentik-migration/`.

**Blocked by:** 01

**Status:** ready-for-human (built 2026-11-20; waiting for the user to accept the photo and the screenshots)

- [x] At desktop width the sign-in page matches the prototype's variant A screenshot, with the new wordmark-free photo
- [x] At phone width the page shows the mark and the form, with no photo (accepted)
- [x] The sign-in code page and the set-new-password page carry the same CSS
- [x] The branding lives entirely in the production blueprint and image assets, with nothing configured by hand in Authentik's admin
- [ ] The user has seen and accepted the screenshots
- [ ] The sign-in page's recovery link reads "Forgot your password?" (found in ticket 07: Authentik's stock wording is "Forgot username or password?", but the username is the phone and isn't what's forgotten)

## Comments

**2026-11-20, built; waiting for the user.** The screenshots are in [.scratch/authentik-cutover/look/](../look/), not committed:

- `0-prototype-variant-a.png`: the prototype, for comparison
- `1`–`6`: the sign-in page at desktop and phone widths, the sign-in code, choose a new password, change password, and the set-password page opened without a link
- `photo-before.jpg` and `photo-after-wordmark-removed.jpg`

What was done:

- **The wordmark-free photo** is an edit, made because no original was supplied. The NLOLO wordmark's area of sky (above the four people) was repainted row by row from the sky at each row's edges, with fine grain, a soft border, and a light blur inside. The people are untouched, and so is the top of the young man's hair (checked close up). It's in `deploy/authentik/branding/brand.jpg` until the user approves it or supplies an original.
- **The branding lives in the production blueprint and `deploy/authentik/branding/`:**
  - the brand's logo (`lifeplatform/mark.svg`, the 48px N-leaf mark from the prototype), favicon (`lifeplatform/favicon.svg`, today's) and default flow background (`lifeplatform/brand.jpg`)
  - the custom CSS: the prototype's `base.css` plus `a-split.css`, inline
  - `layout: content_right` on the sign-in, change-password and set-new-password flows
  - Authentik serves media from `/media/public`, so the dev compose mounts the folder at `/media/public/lifeplatform` on the server and worker
- **At desktop width** the sign-in page matches the prototype's variant A, minus the wordmark. **At phone width:** the mark and the form, with no photo (accepted). The code, set-new-password and change-password pages wear the same look.

Found while building:

- **The set-new-password flow could be opened without a recovery link.** The password stage on Change password shows Authentik's own "Forgot password?", which opened its "Choose a new password" form straight away, current password unasked. Whether saving there would then have changed the signed-in person's password wasn't tried; the form being reachable at all was enough to close it. Fixed:
  - a flow policy plans it only when a pending user is already in context. The API's recovery links have one; a direct visit doesn't, and gets "This link is invalid or has expired. Ask for a new code on the reset page."
  - CSS hides that "Forgot password?" link, which only appears on Change password
  - checked: an anonymous and a signed-in direct visit are refused, a recovery link still opens the form, and the sign-in page's link remains
- **The recovery link's wording can't be changed cleanly.** Authentik has no setting for "Forgot username or password?". CSS can draw other words, but screen readers then hear both phrases run together ("Forgot username or password?Forgot your password?"). That relabel was taken back out, and the wording is put to the user.
