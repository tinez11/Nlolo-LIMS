---
version: 1
slug: "ckend-keycloak-themes-lifeplatform-login-login-ftl"
primary_target: "backend/keycloak/themes/lifeplatform/login/login.ftl"
related_targets: ["backend/keycloak/themes/lifeplatform/login/template.ftl","backend/keycloak/themes/lifeplatform/login/resources/css/login.css"]
---

# Keycloak sign-in — "Split leaf"

**Mode: Operate.** The visitor completes one task: getting in. Nobody is being
persuaded, and being locked out is expensive, so error copy, keyboard reach and
the busy state matter more here than expression does. What expression there is
lives on the brand panel, which is the one half of the page not doing work.

## This surface deliberately does NOT inherit DESIGN.md

DESIGN.md owns the console — "The Ledger": achromatic, hairline-ruled, flat, and
explicitly "not soft, rounded, playful, gradient-lit or glassy". This page is
chromatic, rounded and expressive, built from a reference the client pinned (a
Wise sign-in screen, supplied as a screenshot).

That divergence is an accepted decision, taken after the conflict was raised and
the direction reaffirmed. It is recorded here rather than in DESIGN.md, because
DESIGN.md still describes the console truthfully and this page is not the
console.

**The divergence is settled, not pending.** Migrating the console to this world
was offered, started, and then explicitly called off — the console keeps The
Ledger and this page keeps Split leaf. So the sign-in page and the app behind it
read as two products **on purpose**.

Do not treat that as drift to be tidied up. Specifically: do not repaint
`frontend/src/index.css` in these greens, do not make the console sidebar a
brand panel, and do not soften this page toward the console's palette either.
If the question is reopened it will be reopened deliberately, and it means
replacing DESIGN.md rather than adding to this brief.

## What is borrowed and what is not

**Borrowed from the reference:** the two-panel split; the large radius on the
form panel's top-left corner only; the centred mark / large welcome / small
subline stack; label-above-field inputs with a dark hairline; the full-width
pill primary action; the heavy uppercase display voice on the brand side; the
figure card bleeding off the panel's left edge under a lime disc.

**Not borrowed:** the palette. Every green is derived from the Nlolo mark rather
than lifted from the reference's own brand pair — copying a named company's
exact colours onto its exact layout stops being reference and starts being
imitation. Nlolo is the vendor, not a tenant insurer, so a vendor mark here does
not breach PRODUCT.md's white-label rule; a tenant's brand still would.

## Three slots the reference has that this platform cannot fill

All four realms set `registrationAllowed` and `resetPasswordAllowed` false, and
no realm has an identity provider configured. So "Sign up", "Trouble logging
in?" and the Google / Facebook / Apple row are written as conditionals keyed on
the realm's own flags — each appears by itself the day someone enables it — and
the region the reference spends on third-party buttons carries the true answer
instead: "Accounts and password resets are handled by your administrator."

That sentence is assembled from the flags too, so it stops being printed the
moment it stops being true.

## Fixed decisions

- **Type:** Inter for the interface, Archivo Black for the brand panel's display
  line, both from Google Fonts with real fallback stacks. Self-hosting is the
  production step.
- **Dark mode is authored, not skipped.** The reference commits to one look, but
  this platform treats dark as a first-class peer. Only the form surface
  inverts; the lime action and its deep-green ink are held exactly, because that
  pair is the brand.
- **The realm is named under the headline**, from `realm.displayName` or the
  machine name — never hard-coded. Four realms share this one theme and the same
  credentials do not exist in two of them.
- **The mark is authored SVG**, not a bitmap, so it is crisp from a 16px favicon
  up to the lockup and takes its colour from a token.
- **The brand photograph is an optional drop-in** at
  `login/resources/img/brand.jpg`; the rule that uses it is commented out so an
  absent asset costs no 404. Without it the figure is a flat brand-green card
  under the lime disc, which is a deliberate two-colour composition rather than
  a broken image slot.
- **No close control.** The reference's × belongs to a modal opened from
  somewhere; this page is the entry point and has nowhere to close to.

## Out of scope, and known

Keycloak's **account console** (`/realms/<realm>/account`) is a separate theme
type (`account`) and is still stock Keycloak, blue buttons and all. The SPA does
not link to it, but anyone who lands there sees Keycloak branding. In Keycloak
24 that console is a React app, so theming it is a materially bigger job than
this one.
