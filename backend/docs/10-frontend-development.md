# 10 — Frontend Development

A runbook for running the console (`frontend/`, Vite + React, one SPA with one sign-in:
the staff, agents and customers workspaces in one app) against the real local stack described
in `docs/09-local-development.md`.

Unlike the Next.js portal this replaces, the SPA is **not** a BFF: it is a plain
browser client that calls the backend directly, authenticating via PKCE against a
**public** Authentik client (`lifeplatform-spa`; Keycloak until 2026-11-19) rather than holding a confidential
client secret server-side. The backend has no CORS policy at all, so a `vite dev`
server proxies `/api` to the backend to stay same-origin (`vite.config.ts`).

## Prerequisites

- Everything in `docs/09-local-development.md`'s prerequisites, plus:
- **Node 22 or newer** (the dev machine runs 24; `package.json` declares no `engines`). `npm` ships with it.
- The backend running per `docs/09-local-development.md`'s startup sequence,
  **including the seeder** -- the console has nothing to show without it.
- Authentik up with its blueprints applied (the `lifeplatform-spa` client, the flows, the dev users) -- see
  Gotcha 1. This is the step most likely to go stale, because it is silent when it does.

## Startup order

```bash
# 1. Infra + backend: follow the Startup block in docs/09-local-development.md exactly
#    (containers incl. objectstore-init, migrate.sh, configure-db.sh, the backend, then the seeder).
#    It is not repeated here because two copies drifted apart: this one still started a `minio`
#    service months after the object store became SeaweedFS.
#    The seeder is REQUIRED -- it writes party_id onto the Authentik logins, see Gotcha 2.

# 2. Console (the lifeplatform-spa client comes with Authentik's blueprint -- see Gotcha 1)
cd ../frontend
cp .env.example .env.local        # first run only
npm install
npm run generate:api              # required before typecheck/build -- see below
npm run dev                       # http://localhost:5173
```

### `.env.local`

Copy `.env.example` to `.env.local`. The defaults already match the standard local
stack (`VITE_API_BASE_URL=/api` through the dev proxy, `VITE_AUTH_ISSUER=
http://localhost:9100/application/o/lifeplatform/`, `VITE_AUTH_CLIENT_ID=lifeplatform-spa`) -- there is
nothing to fill in for local development, unlike the old portal's `.env.local`,
which needed a real client secret. There is no client secret here at all: the SPA
is a public PKCE client by construction (see `frontend/PLAN.md` §3).

### After any OpenAPI change

`src/types/api/*.ts` are generated, not hand-written, from `api/openapi/openapi-*.yaml`
(one per module, 25 as of 2026-10-21 -- there is no aggregate spec on this platform) and are
gitignored, so **every fresh checkout needs this before typecheck or build will
succeed**, not just after a spec change:

```bash
npm run generate:api
```

CI runs this as its own step in `frontend-build-and-test` for the same reason --
omitting it there was found and fixed during this console's own code review:
`Typecheck`/`Build` fail with `TS2307 Cannot find module '@/types/api/...'` on any
tree where it has not run yet.

## Testing

Two independent layers, run differently on purpose:

- **`npm test`** (Vitest) -- fast, mocked at the data-shape level (MSW is
  permitted for response bodies), no live stack required. Run in the foreground.
- **`npm run test:e2e`** (Playwright) -- slow, real Authentik sign-in, real backend,
  real Postgres and SeaweedFS. Requires the full stack above (infra, backend with seeded
  data, Authentik's blueprints applied) to already be running, the backend with `SMS_GATEWAY_LIVE=true` so the
  `public` specs can read texted codes from the mock gateway.
  `playwright.config.ts`'s `webServer` starts `npm run dev` itself if port 5173 is
  free, and `reuseExistingServer: !CI` happily attaches to one you already
  started.

  ```bash
  cd frontend
  npm run test:e2e
  ```

  About 60 specs across three projects: `staff` (`staff-*.spec.ts`), `agents`
  (`agents-*.spec.ts`) and `public` (`public-*.spec.ts`: the staff sign-in code and `/reset`, signed out). The `setup`
  project signs in once per role through `e2e/signIn.ts` (`e2e/auth*.setup.ts`: underwriter, admin, assessor, manager,
  management, senior, finance, finance approver, service, agent) and saves each session cookie under `e2e/.auth/`.
  Specs that build fixtures over the API mint tokens with `apiToken` from the same file. The full run is long; run one
  spec while working (`npx playwright test e2e/<spec>`), and if a spec times out in a full run, re-run it alone before
  diagnosing. What gets saved is Authentik's **session cookie**, not app tokens --
  the console holds tokens in memory only (see Gotcha 3), so every subsequent test
  exercises a real silent re-authentication rather than replaying a saved session.

## Gotchas

**1. Editing a blueprint may have NO EFFECT on an already-started stack, silently.** Authentik's whole sign-in
configuration is `deploy/authentik/blueprints/` (production's flows, client, look; dev's test users). The worker
applies a file at start and when its content changes -- but a Windows bind mount sends no file events into the
container, so on this machine an edit is not noticed until you apply it: `docker exec infra-authentik-worker-1 ak
apply_blueprint /blueprints/lifeplatform/lifeplatform.yaml` (from PowerShell; Git Bash rewrites the path), or restart
the worker. That command prints Authentik's own error if the file is invalid. Read the result back -- a token
(`token_for` in `scripts/seed-lib.sh`) or the page itself. That is not decorative: this platform's history is that
its former Keycloak realms went ten milestones with zero protocol mappers before anyone noticed (see
`project-synthetic-test-identity-trap`), because every test minted its own JWT instead of using a real one.

**2. `party_id` comes from the seeder, not the dev blueprint.**
`deploy/authentik/blueprints/dev/dev-users.yaml` sets `tenant_id` on every user but no
`party_id` -- staff have no party of their own, so this affects customers and
agents most directly, but it is the same underlying mechanism: only
`scripts/seed-dev-data.sh` writes the seeded data the console has anything to
show. Skip the seeder and the Policies list is simply empty, which looks
identical to a real backend problem; see `docs/09-local-development.md`'s
Gotcha 4.

**3. Real login only -- there is no mocked auth provider, in the app or in the
E2E suite -- and tokens are held in memory only.** `PlatformAuthProvider.tsx` mounts
one `react-oidc-context` `AuthProvider` at the root, against Authentik's one public
PKCE client (one for everyone since 2026-11-06; one per realm-scoped route before). `/` is the sign-in for
everyone; `/staff`, `/agents`, `/customers` are workspace prefixes, guarded by
`RequireWorkspace`, and `lib/http.ts` sends the prefix's workspace as `X-Workspace`
on every request. `e2e/signIn.ts` drives Authentik's real sign-in pages through a real browser, by what they say
("Username" and "Log in", then "Password" and "Continue"): they are drawn in shadow DOM, which label and role locators
pierce and CSS ids do not. Tokens are never written to `localStorage`/`sessionStorage` -- only the
transient PKCE verifier is, and only until it is redeemed -- which means **every
page load re-authenticates silently against Authentik's session cookie** (about 3 seconds each, against Keycloak's
well under one: give long specs `test.slow()`). This is a
deliberate trade against XSS risk (see `frontend/PLAN.md` §3), and it is also why
this suite's login step is not optional overhead to be mocked away: it is
exercised on literally every navigation. Do not "simplify" the E2E setup by
injecting a session cookie or token directly -- this project's own history (the
M1 database role, the M11 Keycloak mappers) had a fabricated test identity hide a
completely broken real-credential path twice, both times with every test green.

**4. The sign-in is two pages, username then password (2026-11-20).** Between them, five wrong passwords for a login
in 15 minutes lock it (`lifeplatform-login-locked` in the production blueprint): even the right password is then
refused with "Too many wrong passwords". A spec or script that tries wrong passwords on a shared dev login locks it
for every other spec for up to 15 minutes; use a login of its own. `RequireAuth` redirects straight to Authentik --
there is no app-owned sign-in page.

**5. The seeded data is a starting point, not a fixture.** The seeder creates the
realm users' parties, products and a few policies; e2e specs create what they need
themselves. A cash value of zero on a policy is real, not a display bug: a version
with no cash-value table has no surrender value (the Policy page says so), and a
savings account's value is its balance.

**6. Port 15432, not 5432, for any direct Postgres connection from the host.**
The `postgres` container publishes on host port 15432, because a native Postgres on
5432 would silently win that port; `application-local.yml` already points at 15432.
For a quick look, `docker exec infra-postgres-1 psql -U postgres -d lifeplatform`
needs no client install.

**7. After a backend change, restart the backend; after a spec change, regenerate types.**
Vite reloads the console by itself, but the backend (`spring-boot:run`) does not reload,
and `src/types/api/` is regenerated only by `npm run generate:api`.
