# 10 — Frontend Development

A runbook for running the staff console (`frontend/`, Vite + React, realm-scoped
SPA) against the real local stack described in `docs/09-local-development.md`.

Unlike the Next.js portal this replaces, the SPA is **not** a BFF: it is a plain
browser client that calls the backend directly, authenticating via PKCE against a
**public** Keycloak client (`lifeplatform-spa`) rather than holding a confidential
client secret server-side. The backend has no CORS policy at all, so a `vite dev`
server proxies `/api` to the backend to stay same-origin (`vite.config.ts`).

## Prerequisites

- Everything in `docs/09-local-development.md`'s prerequisites, plus:
- **Node 22** (`frontend/package.json`'s `engines`). `npm` ships with it.
- The backend running per `docs/09-local-development.md`'s startup sequence,
  **including the seeder** -- the console has nothing to show without it.
- The `lifeplatform-spa` client actually present in Keycloak -- see Gotcha 1. This
  is the step most likely to be skipped, because it is silent when it is.

## Startup order

```bash
# 1. Infra + backend, exactly as in docs/09-local-development.md
cd infra && docker compose up -d postgres keycloak minio redis mock-mobile-money mailpit && cd ..
scripts/migrate.sh local
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local
scripts/seed-dev-data.sh          # REQUIRED -- writes party_id back into Keycloak, see Gotcha 2

# 2. The lifeplatform-spa Keycloak client -- see Gotcha 1 for why this is a
#    separate step and not just part of the realm import
./scripts/apply-spa-client.sh staff

# 3. Console
cd ../frontend
cp .env.example .env.local        # first run only
npm install
npm run generate:api              # required before typecheck/build -- see below
npm run dev                       # http://localhost:5173
```

### `.env.local`

Copy `.env.example` to `.env.local`. The defaults already match the standard local
stack (`VITE_API_BASE_URL=/api` through the dev proxy, `VITE_KEYCLOAK_BASE_URL=
http://localhost:8081`, `VITE_KEYCLOAK_CLIENT_ID=lifeplatform-spa`) -- there is
nothing to fill in for local development, unlike the old portal's `.env.local`,
which needed a real client secret. There is no client secret here at all: the SPA
is a public PKCE client by construction (see `frontend/PLAN.md` §3).

### After any OpenAPI change

`src/types/api/*.ts` are generated, not hand-written, from `api/openapi/openapi-*.yaml`
(15 separate specs -- there is no aggregate spec on this platform) and are
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
- **`npm run test:e2e`** (Playwright) -- slow, real Keycloak login, real backend,
  real Postgres/MinIO. Requires the full stack above (infra, backend with seeded
  data, and `lifeplatform-spa` applied) to already be running.
  `playwright.config.ts`'s `webServer` starts `npm run dev` itself if port 5173 is
  free, and `reuseExistingServer: !CI` happily attaches to one you already
  started.

  ```bash
  cd frontend
  npm run test:e2e
  ```

  Expected: 7 tests pass (`setup` logs in as `staff.underwriter` once and saves
  Keycloak's SSO cookie to `e2e/.auth/staff.json`; `staff-policies.spec.ts` runs
  against `chromium`). What gets saved is the **SSO cookie**, not app tokens --
  the console holds tokens in memory only (see Gotcha 3), so every subsequent test
  exercises a real silent re-authentication rather than replaying a saved session.

## Gotchas

**1. Editing `keycloak/staff-realm.json` has NO EFFECT on an already-started
stack, silently.** Keycloak keeps its own data in Postgres here (`KC_DB=postgres`,
volume `infra_postgres-data`), and `--import-realm` imports a realm only if it
does **not already exist**. So adding the `lifeplatform-spa` client to the realm
JSON does nothing on any environment that has been started before -- no warning,
no error, the console just fails with an opaque "invalid client" at the Keycloak
login page. A fresh volume (and CI) imports it correctly, which is exactly what
makes this easy to miss. Run `./scripts/apply-spa-client.sh staff` after any
change to the client's config in that JSON file; it is idempotent (create-or-update
via the Admin API) and verifies both the client's PKCE settings and its
`tenant_id` protocol mapper by reading them back -- not by trusting the write's
status code. That last check is not decorative: this platform's own history is
that all four realms went ten milestones with zero protocol mappers before
anyone noticed (see `project-synthetic-test-identity-trap`), because every test
minted its own JWT instead of using a real one.

**2. `party_id` comes from the seeder, not the realm import.**
`keycloak/staff-realm.json` sets `tenant_id` on every staff user but no
`party_id` -- staff has no party of its own, so this affects the customer/agent
realms more directly, but it is the same underlying mechanism: only
`scripts/seed-dev-data.sh` writes the seeded data the console has anything to
show. Skip the seeder and the Policies list is simply empty, which looks
identical to a real backend problem; see `docs/09-local-development.md`'s
Gotcha 4.

**3. Real login only -- there is no mocked auth provider, in the app or in the
E2E suite -- and tokens are held in memory only.** `RealmAuthProvider.tsx` mounts
one `react-oidc-context` `AuthProvider` per realm-scoped route, against a public
PKCE client. `e2e/auth.setup.ts` and `staff-policies.spec.ts` drive the actual
Keycloak login form (`#username`, `#password`, `#kc-login`) through a real
browser. Tokens are never written to `localStorage`/`sessionStorage` -- only the
transient PKCE verifier is, and only until it is redeemed -- which means **every
page load re-authenticates silently against Keycloak's SSO cookie**. This is a
deliberate trade against XSS risk (see `frontend/PLAN.md` §3), and it is also why
this suite's login step is not optional overhead to be mocked away: it is
exercised on literally every navigation. Do not "simplify" the E2E setup by
injecting a session cookie or token directly -- this project's own history (the
M1 database role, the M11 Keycloak mappers) had a fabricated test identity hide a
completely broken real-credential path twice, both times with every test green.

**4. One hop, not two -- the login flow changed shape from the old portal.**
The deleted Next.js portal bounced through NextAuth's own sign-in page first
(`/api/auth/signin`, "Sign in with Keycloak") before reaching Keycloak's real
form. This SPA's `RequireAuth` redirects straight to Keycloak -- there is no
intermediate app-owned page. A script written against the old two-hop flow will
find `#username` immediately after `page.goto()`, without an intervening click.

**5. The one seeded policy is a dead end for new claims and reads
`cashValue: 0.00`.** `scripts/seed-dev-data.sh` leaves exactly one policy,
`POL-6BD5702F`, in status SURRENDERED with one SETTLED claim against it -- see the
old portal runbook's equivalent gotcha (now in git history) for the full
claims-specific detail, which no longer applies directly to this staff-only
console but is worth knowing if a future realm's E2E suite exercises claims. The
`cashValue: 0.00` is not a display bug: `PolicyAccount.cashValueAmount` is
hardcoded to zero at issuance platform-wide (see `project-platform-status`'s M11
entry), and the console's Policy detail page annotates it rather than hiding it.

**6. `pg-hostproxy` (port 15432), not `5432`, for any direct Postgres connection
from the host.** A native Postgres process independently occupies host port 5432
on this machine; connecting there reaches the wrong server. `application-local.yml`
already points at 15432 -- this matters only when inspecting seeded data by hand.
