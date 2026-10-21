# Console architecture

How the frontend (`frontend/`) is built **today** (2026-10-21). For running it, see
`backend/docs/10-frontend-development.md`; for how it should look and read, `DESIGN.md` and `PRODUCT.md` at the repo root.
`PLAN.md` beside this file is the original design log (2026-08) and is historical: where the two disagree, this file
and the code win.

## 1. What it is

One single-page app that serves **three consoles**, one per Keycloak realm:

| Realm | URL prefix | Who | Screens | Sidebar items | Lands on |
|---|---|---|---|---|---|
| `staff` | `/staff` | underwriters, claims, finance, admin | 71 | 38 | a role's dashboard (else its own queue) |
| `agents` | `/agents` | tied agents | 14 | 9 | `dashboard` |
| `customers` | `/customers` | policyholders (the customer portal) | 11 | 8 | `home` |
| `regulators` | -- | reserved; no screens yet | 0 | 0 | -- |

**Stack:** React 19, TypeScript, Vite, React Router 7 (data router), Zustand 5 for state, Axios for HTTP,
react-hook-form + Zod 4 for forms, Tailwind 4 with design tokens in `src/index.css`, Radix primitives for dialogs,
popovers, tabs and tooltips, `react-oidc-context` for login, `openapi-typescript` for API types. No TanStack Query,
no component library beyond Radix.

**The SPA is a plain browser client, not a BFF.** It calls the backend directly with a bearer token. In development
Vite proxies `/api/*` to `http://localhost:8080` (stripping `/api`) so everything is same-origin; on the server Caddy
does the same (`deploy/web/Caddyfile`).

## 2. A request, end to end

```
URL /staff/policies/POL-1
  -> App.tsx: the realm route -> RealmAuthProvider (Keycloak realm "staff") -> RequireAuth -> AppShell (sidebar, header)
  -> screens.tsx: the manifest entry for 'policies/:policyNumber' -> lazyPages.ts: PolicyDetailPage (own chunk)
  -> page reads a Zustand store slice:        usePolicyStore(s => s.detail[policyNumber])
  -> page asks the store to load:             loadPolicy(policyNumber)
  -> store action calls the API module:       api/policies.ts getPolicy()
  -> lib/http.ts (Axios): adds the bearer token, checks the Idempotency-Key on mutations, turns failures into ApiError
  -> backend /policies/POL-1 (via the /api proxy)
  -> the response, typed by src/types/api/policy.ts (generated from backend/api/openapi/openapi-policy.yaml)
  -> store writes Resource<T> {data, status, error, loadedAt}; the page re-renders
```

## 3. The directory map

| Path | What lives there |
|---|---|
| `src/main.tsx`, `App.tsx` | Mount; the router: one subtree per realm, each wrapped in auth and the shell. |
| `src/screens.tsx` | **The screen manifest** -- every route of every realm, its sidebar placement, nav groups and their role gates, each realm's home. The sidebar is derived from it. |
| `src/lazyPages.ts` | Every page as a `React.lazy` import, so a console downloads only the screens it opens. |
| `src/entryPath.ts` | What `/` does: the realm picker in development, the customer portal in production. |
| `src/navBadges.ts` | Sidebar counts of work waiting (each a real paged search's `totalElements`). |
| `src/auth/` | Realms and Keycloak settings (`realms.ts`), the per-realm OIDC provider, `RequireAuth`, reading roles from the token (`claims.ts`), the in-memory token store, return-to-after-login. |
| `src/api/` | One module per backend area: thin typed functions over `lib/http`. `api/types.ts` re-exports the generated schema types under readable names (about 230). |
| `src/types/api/` | **Generated** from the 25 OpenAPI specs by `npm run generate:api`. Gitignored; never edit by hand. |
| `src/store/` | One Zustand store per domain (about 30), all built on `createResourceSlice.ts`. |
| `src/features/<area>/` | The screens, grouped by business area (section 7): pages, their sections and panels, and their form schemas, with tests beside them. |
| `src/components/` | Shared building blocks (section 6); `components/ui/` holds the primitives (button, input, checkbox, badge, sheet, tooltip); `components/charts/` the two charts. |
| `src/gates/` | Business preconditions as pure functions (section 5). |
| `src/lib/` | Cross-cutting helpers: HTTP, errors, idempotency, money, dates, status labels, theme, downloads, search keys. |
| `src/test/` | Vitest setup, a `renderScreen` helper, and `designGuards.test.ts` (section 8). |
| `e2e/` | Playwright specs (`staff-*`, `agents-*`), one login setup per role, session helpers. |
| `scripts/generate-api-types.mjs` | The type generator: one TS module per spec under `backend/api/openapi/`. |

## 4. Routing, realms and access

- **One manifest.** `screens.tsx` holds `STAFF_SCREENS`, `AGENTS_SCREENS` and `CUSTOMER_SCREENS`. Each entry is
  `{ path, element, reach }`, where `reach` is either a sidebar placement `{ group, label, icon, badge? }` or
  `'drill-in'`. Every screen must say how it is reached: a detail page is opened from a row, never from the sidebar.
- **Nav groups carry the role gates.** `NAV_GROUPS[realm]` orders the sidebar and gates whole groups with a predicate
  from `auth/claims.ts` (`canSeeFinance`, `canSeeReports`, ...). Those predicates mirror the backend's `@PreAuthorize`
  expressions word for word. **They hide; they do not protect** -- the backend refuses regardless.
- **Homes.** `REALM_HOME` is the realm's index route; `homeFor()` sends a staff member to their own queue (an
  underwriter to Underwriting, a claims manager to settlement requests, ...) or their dashboard.
- **Realm-scoped login.** `RealmAuthProvider` mounts one OIDC provider per realm, against that realm's
  `lifeplatform-spa` client (public, PKCE). Tokens live **in memory only**; `sessionStorage` holds just the one-time
  PKCE verifier. Every page load therefore re-authenticates silently against Keycloak's SSO cookie.
- **The data router** (`createBrowserRouter`) is what lets `UnsavedGuard` block navigation away from a half-filled form.

## 5. Data, mutations and rules

**Reading.** A store keeps each server resource as `Resource<T>` (`data`, `status: idle|loading|success|error`,
`error`, `loadedAt`). `track()` runs a load and writes those transitions; a refetch keeps the previous data on screen.
Keyed resources (one per policy number, case id, ...) are `Record<key, Resource<T>>`. Selectors return the stored
object and default outside the selector, because a fresh `?? []` inside one re-renders forever.

**Writing.** A mutation is a store action run through `act(key, call)`, which records its own `acting[key]`
resource (so a button can show progress and an `InlineError` the failure) and then re-reads what it changed.
Endpoints that require an `Idempotency-Key` get one minted **once per user intent** by `startMutation()` in the
store action and reused on retry; in development the HTTP layer throws if one is missing.

**Errors.** `lib/http.ts` turns every failure into an `ApiError` (`kind`, status, the server's `detail`, per-field
errors, `traceId`). A 401 triggers a silent sign-in (the token expired between renewals); if the SSO session is
really gone, `RequireAuth` sends the user to the login page. Every request failure is shown with
`InlineError`, which keeps the server's own words and the trace id.

**Money.** Amounts are decimal strings with a currency on the wire (`lib/money.ts`: `formatMoney`, `AMOUNT_PATTERN`,
string comparison helpers). `parseFloat` is banned by lint. Figures come from the server; the console formats them.

**Forms.** react-hook-form with a Zod schema kept beside the page (`*Schema.ts`, `*Form.ts`), validating on touch
(`lib/formTiming`). Schemas mirror the server's validators **message for message**, so the console and a 422 never
disagree. `toApiRequest()` in each schema is the one place form values become a request body.

**Gates.** `src/gates/*Gates.ts` turn already-fetched data into a list of `Gate { ok, hard, title, detail }`: whether
an action may proceed and why not. `hard` means the platform will refuse; soft means "flag it for a person". Pages
render them with `GatePanel`. They are pure functions with their own tests.

## 6. Shared components

| Kind | Components |
|---|---|
| Page structure | `AppShell` (sidebar, header, command palette, theme), `PageHeader` (title, breadcrumb), `DetailLayout` (main column plus an optional pinned record rail), `RecordTabs`, `SectionNav` and `FormOutline` (in-page navigation), `Panel`, `Field` |
| Data display | `DataTable`, `StatusBadge`, `KpiCard`, `StatCards`, `charts/BarChart`, `charts/BucketBars`, `PartyName`, `ProductName`, `AgentName`, `DocumentViewer` |
| Inputs | `FormField`, `DatePicker`, `PartyPicker`, `AgentPicker`, `UnderwritingCasePicker`, `BranchSelect`, `FilterChip`, `ui/input` (`Input`, `Select`), `ui/checkbox` |
| Feedback | `states` (`Spinner`, `LoadingBlock`, `TableSkeleton`, `EmptyState`, `ErrorPanel`, `NoAccess`), `InlineError`, `ConfirmAct` (a deliberate second click for anything irreversible), `Receipt` (what an action just did), `UnsavedGuard`, `GatePanel` |
| Navigation helpers | `CommandPalette` (search across records), `jump.ts`, `listKeys.ts` (keyboard lists), `railPin.ts`, `sectionSpy.ts` |

## 7. Feature areas

Each folder under `src/features/` is a business area. It usually matches a backend module and an API module/store
of the same name.

| Feature folder | Backend module(s) | What it covers | Realm |
|---|---|---|---|
| `party` | party | client register, client record, KYC, editing a client | staff, agents |
| `underwriting` | underwriting, omnichannel | the case queue, opening a case, assessment and decision, quotation calculator, accepted quotations, agent proposals and amendments | staff, agents |
| `policies` | policy, billing, policyloan | issuing, the policy record and its tabs, beneficiaries, loans, surrender, paid-up, group and credit-life schemes | staff, agents |
| `claims` | claims | registering, assessing and settling claims | staff, agents |
| `accounts` | accumulation | the Account tab: ledger, money movements, statements, deposit and term-account sections | staff |
| `annuities`, `unitlinked`, `bonuses` | annuity, unitlinked, bonus | held vestings, funds and prices, bonus declarations | staff |
| `payouts` | benefitpayout | scheduled payouts, the payouts queue, payment runs, free-look | staff |
| `billing`, `payments` | billing, payment | arrears, receipts, the payment register | staff |
| `finaccounting`, `finance` | finaccounting | chart of accounts, GL; the IFRS 17 engine, posting rules, unposted events, manual journals, periods, expense allocation, withholding, year-end close | staff |
| `reinsurance`, `regreporting` | reinsurance, regreporting | treaties, cessions, bordereaux and statements; regulatory returns | staff |
| `distribution` | distribution | agents, hierarchy, commission plans and statements | staff, agents |
| `products` | product, accumulation, unitlinked | the product catalogue, publishing a version (every value basis), the premium grid, account charges | staff |
| `reporting`, `dashboard` | reporting, dashboard | management and accounting reports; the role dashboards | staff, agents |
| `communications`, `audit` | communication, audit | messages sent, templates; the event journal | staff |
| `documents` | document, omnichannel | downloads: policy schedule, payment schedule, statements | staff, customers |
| `customer` | omnichannel (customer portal) | the customer portal: home, policies, claims, documents, products, applications, paying a premium, messages | customers |

`features/RealmPicker.tsx` is the development entry page at `/`.

## 8. Rules the build enforces

- **Lint** (`eslint.config.mjs`): React hooks rules (including no `setState` directly in an effect body); no
  `parseFloat` (money is a string); no raw `border-input` class (use the primitives).
- **Design guards** (`src/test/designGuards.test.ts`, part of `npm test`): only colour tokens the theme defines; no
  text under 12px; checkboxes through `ui/checkbox`; no alpha-dimmed text; a button keeps its label while its request
  runs; every request failure through `InlineError`; a breadcrumb rather than a back link; panels through `Panel`;
  sticky horizontal scrollers clip their vertical axis.
- **Types**: `npm run build` runs `tsc --noEmit` first; the generated API types make every request and response
  checked against the OpenAPI specs.

## 9. Tests

- **Vitest** (`npm test`): about 190 test files beside the code they cover -- schemas, gates, stores, components and
  pages, in jsdom, with MSW or stubbed stores. No backend needed.
- **Playwright** (`npm run test:e2e`): about 60 specs against the real stack and a real Keycloak login -- 57
  `staff-*` and 4 `agents-*`. **The customer portal has no end-to-end spec.** Logins are saved per role under
  `e2e/.auth/` by the `auth*.setup.ts` projects. An axe accessibility baseline lives in `e2e/a11y-baseline.json`.

## 10. Configuration

- `.env.local` (copy `.env.example`): `VITE_API_BASE_URL` (`/api`), `VITE_KEYCLOAK_BASE_URL`
  (`http://localhost:8081`), `VITE_KEYCLOAK_CLIENT_ID` (`lifeplatform-spa`).
- `vite.config.ts`: port 5173, fixed because the Keycloak client's redirect URI is; the `/api` proxy (target
  `VITE_PROXY_TARGET`, default `http://localhost:8080`); the `@` alias for `src/`.

## 11. Known gaps

- `regulators` has a realm and a Keycloak client but no screens.
- The customer portal is untested end to end.
- `PLAN.md` describes the 2026-08 design and is not kept current.
- Generated types are not committed: a fresh checkout, or any change under `backend/api/openapi/`, needs
  `npm run generate:api` before it builds.
