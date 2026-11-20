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

**Status:** ready-for-agent

- [ ] At desktop width the sign-in page matches the prototype's variant A screenshot, with the new wordmark-free photo
- [ ] At phone width the page shows the mark and the form, with no photo (accepted)
- [ ] The sign-in code page and the set-new-password page carry the same CSS
- [ ] The branding lives entirely in the production blueprint and image assets, with nothing configured by hand in Authentik's admin
- [ ] The user has seen and accepted the screenshots
- [ ] The sign-in page's recovery link reads "Forgot your password?" (found in ticket 07: Authentik's stock wording is "Forgot username or password?", but the username is the phone and isn't what's forgotten)
