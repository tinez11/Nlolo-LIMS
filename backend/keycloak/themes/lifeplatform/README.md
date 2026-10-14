# `lifeplatform` — Keycloak login theme

**"Split leaf."** A two-panel sign-in built from a client-pinned reference, in
Nlolo's brand.

Built against **Keycloak 24.0** (`quay.io/keycloak/keycloak:24.0`), the version
pinned in `infra/docker-compose.yml`.

> **This surface deliberately does not inherit `DESIGN.md`.** That file owns the
> console — "The Ledger": achromatic, hairline-ruled, flat, explicitly "not
> soft, rounded, playful, gradient-lit or glassy". This page is chromatic,
> rounded and expressive, and the divergence is an accepted decision rather than
> drift. The reasoning, what was borrowed from the reference and what was not,
> and the consequence to keep in view are recorded in
> `.impeccable/surfaces/…-login-ftl.md`. **Read that before changing the look.**
>
> Migrating the console to this world was offered, started, and explicitly
> called off. The two surfaces look different **on purpose** — do not reconcile
> them by softening this page or repainting the console.

## Two compositions, decided by whether the realm carries the photograph

**Photograph and gloss** — `staff`, `agents`, `customers`. Half the shell for
the brand, half for the form. In the brand half the photograph takes the top
**75%** at full bleed, and the remaining quarter is the same picture blurred
into a glossy ground that the sharp frame dissolves into over its last tenth.
The panel is filled by the image rather than by a colour that happens to sit
beside it.

**Split leaf** — `regulators`, which has no photograph. The original
composition, and the one the rest of this design is built around:

- A deep-green **brand panel** on the left: a figure card bleeding off the panel
  edge under a lime disc, and a heavy uppercase display statement at the bottom.
- A **form panel** on the right whose top-left corner is the only large radius on
  the shell, so the brand green reads through that one corner. Square at the
  bottom on purpose: the asymmetry is what makes it a gesture rather than a
  rounded box.
- Inside it, the reference's stack: centred mark, a large welcome, the realm
  named beneath it, label-above-field inputs, and a full-width pill action.

Both compositions keep that corner — it is the reason the form stayed a panel
rather than becoming a card.

**The palette is Nlolo's, not the reference's.** Every green is derived from the
Nlolo mark rather than lifted from the reference's own brand pair — copying a
named company's exact colours onto its exact layout stops being reference and
starts being imitation. Nlolo is the vendor, not a tenant insurer, so a vendor
mark here does not breach PRODUCT.md's white-label rule; a tenant's brand still
would.

## Three slots the reference has that this platform cannot fill

Every realm sets `registrationAllowed` and `resetPasswordAllowed` false, and no
realm has an identity provider. So **"Sign up"**, **"Trouble logging in?"** and
the **Google / Facebook / Apple** row are written as conditionals keyed on the
realm's own flags — each appears by itself the day someone enables it — and the
region the reference spends on third-party buttons carries the true answer
instead: *"Accounts and password resets are handled by your administrator."*
That sentence is assembled from the same flags, so it stops being printed the
moment it stops being true.

## The brand photograph

`login/resources/img/brand.jpg` (1536×1024) leads the brand panel on the three
realms a person signs into — `staff`, `agents`, `customers`. It is a real
`<img>`, not a CSS background: it carries the Nlolo wordmark, so it is content
and has to reach a screen reader. Where it shows, the SVG lockup in the form
column is suppressed — **one mark per screen**.

`regulators` keeps the designed no-photo composition: a flat brand-green card
under the lime disc, with the display statement below it.

**It fills its frame and crops; it is not shown whole.** How much of the image
survives depends on the window, and all of these are measured, not estimated:

| Window | Image shown | Whole figures |
| --- | --- | --- |
| 1440×900, 1920×1080 | 77% | 2 of 4 |
| 1366×720 | 82% | 2 of 4 |
| 1280×672 | 87% | 2 of 4 |
| 1273×573 | 98% | 4 of 4 |
| 390×844 (stacked) | 100% | 4 of 4 |

The wordmark is whole in every one of them. The pattern is `cover`'s two
scaling regimes: on a tall window the frame is squarer than the picture, so it
crops the sides — the outer two people — and on a short one it crops nothing but
a sliver of sky. `--n-photo-share` is the single dial. Lowering it to 65% would
show 88% at 1440 instead of 77%, at the cost of a bigger gloss band and smaller
faces.

Two rules hold the design together:

- **`object-position: 50% 0%`, everywhere.** The wordmark occupies the top 24.4%
  of the source, so wherever `cover` crops vertically the top is what must
  survive. An earlier `50% 32%` bias opened its window *inside* the letters on
  any window under 60rem that was not tall — measured at 844×390 and 900×700.
  `0%` cannot do that at any aspect, which is why there is one value and no
  override.
- **The gloss is a reflection, not a blur.** `.lp-shell--photo .lp-brand::before`
  is the same width and height as the frame and takes the same `cover`
  geometry, so it resolves to the identical crop at the identical scale;
  flipped about its own centre, the edge meeting the frame is the frame's own
  bottom edge, mirrored, and the rest is clipped away. An earlier version
  blurred the picture at 48px across the whole shell instead, which put a smear
  of an unrelated part of it under the frame — an orange shoulder beneath a
  laptop. Nothing lined up because nothing was meant to.

  The frame has **no** bottom fade: a reflection meets the thing it reflects at
  a hard edge, and that edge is what makes the eye read it as one. It is a
  background rather than a second `<img>` because it carries no information and
  must not reach a screen reader; the file is in cache, so it costs no second
  request.

- **Both corners read the photograph, not the shell.** The form panel's one
  large radius is this design's signature and is supposed to reveal the brand.
  Beside a green panel the shell's own green *was* the brand; beside a
  photograph it was a dark wedge. So the brand panel bleeds right, under the
  form panel, by exactly that radius. Stacked, where the panels sit one above
  the other, the form panel rides **up** over the band by the same amount, for
  the same reason.

Below 60rem wide the page stacks — a photograph band over a form panel — and the
frame takes the image's own 3:2 instead of 75% of a column height it no longer
has. There is no reflection there: the frame fills the band, so there is nothing
beneath it to reflect into.

### What this composition replaced, and why

Both predecessors are worth knowing about, because each failed in a way the
current one is shaped to avoid:

- **A 1.4fr / 1fr split.** `cover` scaled by height and threw away 16% of the
  image width from each side. 67% shown, two of four people whole.
- **A full-bleed photograph with the form floating over it as a capped card.**
  87% shown and all four people whole, but the card had to be held clear of the
  wordmark, and on a 1273×573 viewport — an ordinary half-height browser window —
  that left it 281px for 415px of form. It scrolled at rest and cut the sign-in
  button in half. **A full-height form panel cannot do this**, which is the
  reason the split came back.

The **mark itself is authored SVG**, not a bitmap: the same geometry serves the
lockup and the 16px favicon, and it takes its colour from a token.

### Verifying a change without running Keycloak

Do not stand up Keycloak for a CSS change. Reproduce `template.ftl`'s DOM in a
harness HTML, `<link>` the **real** `login.css` and `brand.jpg` by absolute
`file:///` URL so there is zero drift from the shipped files, and drive it with
Playwright from `frontend/`. Measuring the image's box and `object-position` and
computing the `cover` window against the wordmark's known source bounds (X
21–69%, Y 3.4–24.4%) answers the crop question far more reliably than looking at
screenshots. Caveat: `.lp-col` has a 320ms entrance animation, and an early
capture photographs it at `opacity: 0` — a blank form panel in a screenshot is
the harness, not a bug.

## Files

| File | What it owns |
| --- | --- |
| `login/theme.properties` | `parent=base`, and the kc\* class map that makes every un-overridden template inherit this design |
| `login/template.ftl` | The shell. Implements base's `registrationLayout` macro contract, so all ~40 login-flow pages render inside it |
| `login/login.ftl` | The front door. The only page with bespoke structure |
| `login/login-page-expired.ftl` | Overridden for copy: base's version renders two identical "click here" links and no statement of what either costs |
| `login/resources/css/login.css` | The whole visual system: tokens, the shell, both themes |
| `login/resources/js/login.js` | Password reveal, Caps Lock, single-submit guard. All additive |
| `login/resources/js/passwordVisibility.js` | **A deliberate no-op that shadows base's file of the same name.** Base's version wires the same `[data-password-toggle]` contract, so both handlers fired on one click and the reveal on every inherited password page looked live and did nothing. Do not delete it |
| `login/resources/img/favicon.svg` | The Nlolo mark as authored geometry, theme-aware |
| `login/messages/messages_en.properties` | Voice. Only the strings whose wording is wrong here |

**Bespoke:** the login page and the expired page. **Inherited and presentable:**
update password, TOTP enrol and challenge, the authenticator chooser, recovery
codes, logout confirm, the info and error pages — they render in this shell and
pick up the type, spacing, controls and both themes through the class map, but
their structure is base's.

## Wiring

Two things have to be true, and they are independent:

1. **The theme is on disk where Keycloak can see it.** `docker-compose.yml` mounts
   `../keycloak/themes/lifeplatform` at `/opt/keycloak/themes/lifeplatform:ro`.
   A folder theme needs no `kc.sh build` — it is read at runtime.
2. **The realm points at it.** `loginTheme: "lifeplatform"` in each of the four
   realm JSONs.

### The trap: `--import-realm` does not re-import

Keycloak imports a realm JSON only when the realm does not already exist. On any
environment where the realms have been imported once — which is every dev machine
that has run this stack — editing `staff-realm.json` changes nothing. The realm
lives in Postgres now.

To apply it to a running instance:

```bash
docker exec infra-keycloak-1 bash -c '
  KC=/opt/keycloak/bin/kcadm.sh
  $KC config credentials --server http://localhost:8080 --realm master \
    --user admin --password devadmin
  $KC update realms/staff      -s loginTheme=lifeplatform -s "displayName=Staff console"
  $KC update realms/agents     -s loginTheme=lifeplatform -s "displayName=Agent portal"
  $KC update realms/customers  -s loginTheme=lifeplatform -s "displayName=Customer access"
  $KC update realms/regulators -s loginTheme=lifeplatform -s "displayName=Regulator access"
'
```

On Git Bash for Windows, prefix that with `MSYS_NO_PATHCONV=1` or the shell
rewrites `/opt/...` and `http://...` into Windows paths.

Or do it in the admin console at <http://localhost:8081> → *Realm settings* →
*Themes* → *Login theme*.

The realm JSONs still carry `loginTheme`, so a fresh volume gets it automatically.

## Iterating

`start-dev` disables theme and template caching, so an edit is live on the next
page load. Two ways to get an edit in front of the server:

```bash
# Recreate the container so the compose mount takes effect (once, after pulling)
docker compose -f infra/docker-compose.yml up -d keycloak

# Or push the current working tree into a container that is already running
docker cp keycloak/themes/lifeplatform/login/. \
  infra-keycloak-1:/opt/keycloak/themes/lifeplatform/login/
```

Fastest page to look at, no client set-up needed:
<http://localhost:8081/realms/staff/account> — the account console bounces
straight to the login form.

## Production hardening, not done here

- **Self-host Inter.** The stylesheet loads it from Google Fonts exactly as
  `frontend/index.html` does, with `display=swap` and the console's full system
  fallback stack behind it, so a blocked CDN costs the typeface and nothing else.
  For an insurer's own deployment, ship the woff2 files under
  `login/resources/fonts` and `@font-face` them.
- **`realmLabel` comes from Keycloak.** The running head shows `displayName`, or
  the machine realm name where an administrator set none. Nothing about the realm
  is hard-coded, which is what keeps one theme serving four realms and the
  console white-label.
- **`customers` and `regulators` authenticate into an app with no screens.**
  That is a recorded product decision, not a theme bug (`PRODUCT.md`,
  `frontend/PLAN.md` §12.3). Their `displayName`s here are deliberately neutral —
  "Customer access", not "Customer portal" — but if either realm stays empty, the
  honest fix is upstream of this theme.
