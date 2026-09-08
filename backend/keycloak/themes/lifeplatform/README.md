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

## The composition

- A deep-green **brand panel** on the left: a figure card bleeding off the panel
  edge under a lime disc, and a heavy uppercase display statement at the bottom.
- A **form panel** on the right whose top-left corner is the only large radius on
  the shell, so the brand green reads through that one corner. Square at the
  bottom on purpose: the asymmetry is what makes it a gesture rather than a
  rounded box.
- Inside it, the reference's stack: centred mark, a large welcome, the realm
  named beneath it, label-above-field inputs, and a full-width pill action.

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

## Adding the brand photograph

Drop a file at `login/resources/img/brand.jpg`, then uncomment the
`.lp-brand__figure` background-image rule in `login/resources/css/login.css` —
it is commented out so an absent asset costs no 404 on every page load.
`background-position` is set low, so a photograph carrying a wordmark across its
top crops to the subject rather than repeating the mark already in the form
column. With no file, the figure is a flat brand-green card under the lime disc.

The **mark itself is authored SVG**, not a bitmap: the same geometry serves the
lockup and the 16px favicon, and it takes its colour from a token.

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
