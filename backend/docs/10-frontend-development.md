# 10 — Frontend Development

A runbook for running the customer portal (`frontend/customer-portal/`, Next.js) against the real
local stack described in `docs/09-local-development.md`. The portal is a BFF: every browser
request goes through the portal's own Route Handlers, which hold the Keycloak client secret and
call the backend server-side. The backend itself has no CORS policy at all, deliberately, so a
browser could not call it directly even if it tried.

## Prerequisites

- Everything in `docs/09-local-development.md`'s prerequisites, plus:
- **Node** (the version pinned in `frontend/customer-portal/package.json`'s `engines`, if present;
  otherwise a current LTS). `npm` ships with it.
- The backend running per `docs/09-local-development.md`'s startup sequence, **including the
  seeder** -- the portal has nothing to show without it (see Gotcha 1 below).

## Startup order

```bash
# 1. Infra + backend, exactly as in docs/09-local-development.md
cd infra && docker compose up -d postgres keycloak minio redis mock-mobile-money mailpit && cd ..
scripts/migrate.sh local
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local
scripts/seed-dev-data.sh          # REQUIRED -- writes party_id back into Keycloak (Gotcha 1)

# 2. Portal
cd frontend/customer-portal
cp .env.example .env.local        # first run only -- see below
npm install
npm run dev                       # http://localhost:3000
```

### `.env.local`

Copy `.env.example` to `.env.local` and fill in real values for this stack:

| Variable | Value for the standard local stack |
|---|---|
| `BACKEND_BASE_URL` | `http://localhost:8080` |
| `KEYCLOAK_ISSUER` | `http://localhost:8081/realms/customers` |
| `KEYCLOAK_CLIENT_ID` | `lifeplatform-app` |
| `KEYCLOAK_CLIENT_SECRET` | `dev-secret-customers` (the `customers` realm's confidential client secret) |
| `AUTH_SECRET` | any random value -- generate with `openssl rand -base64 32` |
| `AUTH_URL` | `http://localhost:3000` |
| `REDIS_URL` | `redis://localhost:6379` |

`.env.local` (and every other `.env*` file except `.env.example`) is gitignored -- it is never
committed, so every developer creates their own from `.env.example`.

### After any OpenAPI change

The portal's `src/types/api/*.ts` files are generated, not hand-written, from
`api/openapi/openapi-*.yaml` (15 separate specs -- there is no aggregate spec on this platform).
Regenerate them whenever a spec changes:

```bash
npm run generate:api
```

## Testing

Two independent layers, run differently on purpose:

- **`npm test`** (Vitest) -- fast, mocked, no live stack required. Run in the foreground.
- **`npm run e2e`** (Playwright) -- slow, real Keycloak login, real backend, real Postgres/MinIO.
  Requires the full stack above (infra, backend with seeded data, and the portal's own dev server)
  to already be running. `playwright.config.ts`'s `webServer` will start `npm run dev` itself if
  port 3000 is free, but `reuseExistingServer: true` means it happily attaches to one you already
  started -- the usual case in local development.

  ```bash
  cd frontend/customer-portal
  npm run e2e
  ```

  Expected: 3 tests pass (`setup` logs in as `customer.owner` once and saves the session to
  `e2e/.auth/owner.json`; `claim-evidence.spec.ts` and `ownership.spec.ts` run against `chromium`).
  `fullyParallel` is off and `retries` is `0` deliberately -- this suite shares real backend state
  across specs, and a flaky auth failure here is exactly the failure this suite exists to catch, so
  it must fail loudly rather than be quietly retried away.

  `claim-evidence.spec.ts` additionally logs in as `staff.manager` via a direct Keycloak password
  grant (not through the browser) in a `beforeAll`, to reopen `customer.owner`'s seeded claim if it
  is still SETTLED -- see that file's own comments for why. This makes `npm run e2e` self-contained
  from a freshly seeded stack; no manual fixture step is required.

## Gotchas

**1. `party_id` comes from the seeder, not the realm import -- and the portal cannot tell you why
everything is empty.** `keycloak/customers-realm.json` sets `tenant_id` on `customer.owner` and
`customer.other` but deliberately no `party_id` (a party's UUID does not exist until
`POST /parties/individuals` creates it). Only `scripts/seed-dev-data.sh` writes it back into
Keycloak. Skip the seeder and login still succeeds -- the portal shows a signed-in session -- but
every list (`/`, `/claims`) renders empty and every detail page shows a fetch error, because the
backend 403s a token with `tenant_id` but no `party_id` on every ownership-checked endpoint. This
looks exactly like a portal bug (or a broken realm import) and is neither; see
`docs/09-local-development.md`'s Gotcha 4 for the full explanation and the fix (run the seeder).

**2. Real login only -- there is no mocked auth provider, in the app or in the E2E suite.**
`src/auth.ts` configures exactly one NextAuth provider, `Keycloak`, against whichever realm
`KEYCLOAK_ISSUER` points at. `e2e/auth.setup.ts` and `e2e/ownership.spec.ts` drive the actual
Keycloak login form (`#username`, `#password`, `#kc-login`) through a real browser, not a stubbed
session cookie. This is deliberate: this project's own history (the M1 database role, the M11
Keycloak mappers) had a fabricated test identity hide a completely broken real-credential path
twice, both times with every test green. Do not "simplify" the E2E setup by injecting a session
token directly -- that reintroduces exactly the blind spot this suite exists to close.

**3. The NextAuth sign-in page is not the Keycloak login form -- there are two hops.** Visiting any
protected route while unauthenticated redirects to NextAuth's OWN sign-in page
(`/api/auth/signin`), which renders one button per configured provider ("Sign in with Keycloak").
Only after that button is clicked does the browser reach Keycloak's actual login form. A Playwright
script (or a developer) that tries to fill `#username` immediately after `page.goto('/')` will find
no such element yet.

**4. The one seeded policy is a dead end for new claims, and the one seeded claim is a dead end for
new evidence -- know both before writing a new E2E spec against this data.**
`scripts/seed-dev-data.sh` leaves exactly one policy, `POL-6BD5702F`, in status SURRENDERED (a
terminal status -- `PolicyApiImpl.reinstatePolicy` only accepts LAPSED), and exactly one claim
against it, SETTLED (evidence uploads to a SETTLED claim are rejected by
`ClaimsApiImpl.attachEvidence`). Registering a *new* claim against the seeded policy is therefore
not possible (`ClaimsApiImpl.registerClaim` requires the policy to be in force), so the only way to
exercise the evidence-upload path is to move the *existing* claim out of SETTLED first, using the
platform's own `POST /claims/{id}/reopen` (`CLAIMS_MANAGER` role, `staff` realm -- e.g.
`staff.manager` / `devpassword`). `claim-evidence.spec.ts` does this itself in a `beforeAll`; do
the same in any new spec that needs a claim in an editable state, rather than hand-editing the
database or the seeder.

**5. `pg-hostproxy` (port 15432), not `5432`, for any direct Postgres connection from the host.** A
native Postgres process independently occupies host port 5432 on this machine; connecting there
reaches the wrong server. This does not affect the portal directly (it never talks to Postgres
itself -- only the backend and the `IdempotencyStore`'s Redis client do), but it matters the moment
you inspect seeded data by hand while debugging a portal issue.
