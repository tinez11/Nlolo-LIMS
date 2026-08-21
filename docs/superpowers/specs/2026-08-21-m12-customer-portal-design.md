# M12 — Customer Portal (frontend)

**Status:** design approved, pending implementation plan
**Date:** 2026-08-21
**Depends on:** M0–M11 (all merged to `main` at `5d5a1d1`, 661/661 tests green). M11 specifically —
without it, no real Keycloak login could reach any endpoint, and claim evidence had no download
path at all.

---

## 1. Why this is scoped as it is

This is the first frontend on the platform. Ten backend milestones plus M11's loginable-environment
work produced a REST surface, an identity provider, and a seeded local stack — but nothing has ever
rendered a screen against any of it. The scope here is the **customer realm only**: agent, staff,
and regulator portals are separate future projects (per the decomposition agreed before this spec
was written), and building all four at once would repeat M11's own lesson about scope explosion.

Everything in this spec is scoped to what the backend can genuinely do today, verified against the
real controllers rather than the aspirational parts of `docs/04-api-contracts.md` — the same
discipline that made M11 necessary in the first place.

## 2. Architecture

**Next.js (App Router) as a BFF.** Every backend call happens server-side — in Route Handlers or
Server Actions — never from the browser. This is not a preference, it's forced by the platform's
own security model: `SecurityConfig` has no CORS policy at all (verified during M11), by design,
because the confidential `lifeplatform-app` Keycloak client's secret must never reach a browser.
Server-side calls sidestep CORS entirely and keep the token where a script can't touch it.

**Auth: NextAuth, Keycloak provider, `customers` realm only.** Authorization Code + PKCE, the
access token stored in an httpOnly session cookie. `lifeplatform-app`'s `redirectUris` already
include `http://localhost:3000/*` (added in M11 specifically for this). The portal never
constructs, stores, or reads an agent/staff/regulator token — those realms are out of scope by
construction, not by convention.

**A generated TypeScript client, one module per OpenAPI spec.** There is no aggregated spec
(confirmed in M11 — 14 separate YAML files, no `springdoc`/live `/v3/api-docs`), so
`openapi-typescript` runs once per spec this portal actually needs:
`openapi-party.yaml`, `openapi-product.yaml`, `openapi-underwriting.yaml`, `openapi-policy.yaml`,
`openapi-billing.yaml`, `openapi-payment.yaml`, `openapi-claims.yaml`, `openapi-policyloan.yaml`,
`openapi-document.yaml`, `openapi-refdata.yaml`, plus `openapi-common.yaml` for shared schemas
(`Money`, `ProblemDetails`). Each becomes its own typed client module; there is no cross-module
merge step, because the backend doesn't have one either.

**Money stays a string end to end — confirmed against the actual schema, not assumed.**
`openapi-common.yaml`'s `Money` schema is:
```yaml
Money:
  type: object
  properties:
    amount: { type: string, pattern: '^-?\d+(\.\d{1,2})?$' }
    currencyCode: { type: string, pattern: '^[A-Z]{3}$' }
  required: [amount, currencyCode]
```
so the generated TypeScript type is genuinely `{ amount: string; currencyCode: string }` — the
client must not "helpfully" coerce `amount` to a `number` anywhere, which would silently reintroduce
float rounding on a field the backend went out of its way to keep exact. One shared
`formatMoney(amount: string, currencyCode: string)` utility is the only place that ever parses a
money string, so a formatting bug is fixable in one file rather than hunted across every screen.

**No i18n layer.** English-only for v1, no `next-intl`/translation-key scaffolding — the backend
itself has no locale concept (every `ProblemDetail` message is English), so a translation layer now
would translate content the backend can't yet vary anyway.

**UI and data-fetching, carried over from the original stack decision (recorded here since the
condensed design walkthrough didn't re-state them, and they're still the approved choice):**
Tailwind CSS + shadcn/ui for components — unstyled-by-default primitives suit a from-scratch domain
UI better than a heavier design system, and shadcn's copy-into-your-repo model avoids a runtime
dependency on a component library that would need its own upgrade cadence. TanStack Query for
client-side data-fetching/caching/mutation state (loading/error/retry) on top of the generated
client and the BFF's Route Handlers — it is what gives the §6 submit-guards a real, tested
`isPending` state to disable a button on, rather than hand-rolled loading flags per form.

## 3. Token refresh — a real requirement, not a nice-to-have

Checked directly rather than assumed: `keycloak/customers-realm.json` sets no
`accessTokenLifespan`/`ssoSessionIdleTimeout`/`ssoSessionMaxLifespan` overrides, so Keycloak's
server-wide default applies — a fresh Keycloak install's default access-token lifetime is **5
minutes**. A customer reading a policy detail page for longer than that, with no refresh handling,
gets a 401 from the backend on their next click for no reason a customer could ever understand. This
is not an edge case to defer; it will happen inside the first few minutes of real use.

**NextAuth's `jwt` callback holds the refresh state.** On initial sign-in, persist `access_token`,
`refresh_token`, and `expires_at` (computed as `Date.now() + expires_in * 1000` from Keycloak's
token response) into the NextAuth JWT. On every subsequent callback invocation, check
`Date.now() >= expires_at`; if expired, call Keycloak's token endpoint with
`grant_type=refresh_token` (the confidential client's secret is available server-side, same as the
initial code exchange) and persist the new pair. **If the refresh call itself fails** (refresh token
expired, revoked, or the session genuinely idle past `ssoSessionIdleTimeout`), clear the NextAuth
session and force a real re-login — do not surface a raw error, since an idle-timeout logout is
expected, correct behavior, not a bug. Every Route Handler/Server Action that calls the backend
reads the access token from the (already-refreshed-if-needed) session, never a token cached earlier
in the request lifecycle.

## 4. Screens, mapped to real, currently-working endpoints only

Every screen below is scoped to an endpoint verified reachable and correct during this session's
platform-readiness review and M11. Nothing here is aspirational.

| Screen | Endpoint(s) | Notes |
|---|---|---|
| Dashboard | `GET /policies` | **Real pagination and filtering, verified against the spec**: `page`/`pageSize` and a `status` enum filter (`PROPOSED, ACTIVE, LAPSED, SUSPENDED, SURRENDERED, MATURED, REINSTATED`) both exist as query params. Build a real paginated list with status tabs/filter, not a client-side-sliced full fetch. Policy cards show status, next-due invoice via `GET /policies/{n}/invoices/next-due` |
| Policy detail | `GET /policies/{n}`, `GET /policies/{n}/coverage-status`, `GET /policies/{n}/surrender-value` | Cash value / surrender quote rendered as-is (§6) |
| Beneficiaries | `PUT /policies/{n}/beneficiaries` | Update, not create-from-scratch — a policy always has beneficiaries from issuance |
| Endorsements | `POST /policies/{n}/endorsements` | **No server-side idempotency — hard-guarded** (§6) |
| Billing | `GET /policies/{n}/invoices`, `POST /invoices/{id}/payment-request` | `GET .../invoices` supports a `status` filter (`DUE, PARTIALLY_PAID, PAID, IN_GRACE, OVERDUE, WAIVED`) but **no pagination** — verified: the endpoint takes no `page`/`pageSize` params, and doesn't need them, since a policy's invoices are pre-generated ~12 months ahead (a bounded, small list by construction). Build status-filter tabs, not a pager. Payment-request idempotency IS enforced (§6) |
| Claims list/detail | `GET/POST /claims`, `GET /claims/{id}` | **Real pagination and filtering, verified against the spec**: `page`/`pageSize` (default `pageSize=20`, max `100`) and a `status` enum filter both exist. A `claimantPartyId` filter also exists but the spec itself documents it as ignored/overridden for customer tokens — the portal never sends it, since the backend always self-scopes a customer to their own claims regardless |
| Claim evidence | `POST/GET /claims/{id}/evidence`, `GET /claims/{id}/evidence/{ref}` | The download endpoint M11 built. First real proof of the whole stack. |
| Policy loan | `POST/GET /policies/{n}/loans`, `GET /loans/{id}`, `POST /loans/{id}/repayments` | **No server-side idempotency on either POST — hard-guarded** (§6). Currently always 409s (§6) — build the screen, expect it to be inert until the backend gap is fixed |
| Reference info | `GET /reference-codes/{key}` for the 5 customer-readable keys | Informational only (§5) |

**Explicitly not built, and why:**
- **Policy surrender action.** `GET /policies/{n}/surrender-value` (the quote) works and is built.
  `POST /policies/{n}/surrender` and `GET /policies/{n}/processes/{id}` both return a clean,
  well-formed `501 CHOREOGRAPHY_NOT_IMPLEMENTED` (Camunda decision still pending) — the button is
  shown, disabled or leading to an explanation screen, never wired to a submission flow that can't
  complete.
- **KYC upload.** `POST /parties/{partyId}/kyc` is staff-only (`hasRole('REALM_STAFF')`) — no
  customer-reachable path exists.
- **A general "documents" or "my files" screen.** M11 confirmed exactly one document producer
  exists platform-wide: claim evidence. A generic documents list would be permanently empty and
  would misrepresent what the platform can do.
- **Notifications / inbox.** `communication`/`omnichannel` are still empty M0 stub packages —
  confirmed zero feature commits, zero listener classes. Nothing to build against.

## 5. Reference data — display only, and only the allowed keys

`GET /reference-codes/{codeSetKey}` is allowlisted per realm (M11). The customer-readable set is
exactly:

```
TZ_CONTESTABILITY_MONTHS, TZ_REINSTATEMENT_WINDOW_MONTHS, TZ_SUSPENSION_TO_LAPSE_MONTHS,
TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE, DUNNING_ESCALATION_DAYS
```

verified directly against `ReferenceDataController.PUBLICLY_DISCLOSED`. The portal only ever
requests these five, by name — never a discovery call (none exists), never a key outside this list.
Every value renders with the platform's own placeholder caveat surfaced verbatim
(`refdata/V1`'s seed comment: these are placeholders pending Legal/Compliance/Product/Actuarial
sign-off) — e.g. the loan-interest-rate display on the Policy Loan screen carries a small
"provisional rate" note, not presented as a confirmed number.

## 6. Known backend constraints this design deliberately works around

**Cash value and surrender quote are real `TZS 0.00` for every policy, platform-wide.**
`PolicyAccount.cashValueAmount` is hardcoded to zero at issuance with no production code path
crediting it (pre-existing since M3, confirmed during M11's final review). Per the approved
decision: **render it exactly as the API returns it, no caveat, no hiding.** It is not a portal bug
and the portal does not pretend otherwise. Revisit once the backend credits real values — this is a
one-line follow-up then, not a redesign, because the display path already exists.

**Policy loan origination will currently always 409.** Same root cause as above —
`reserveLoanValue` rejects any positive request against a zero cash value. Build the screen and its
error state (a clean, readable "insufficient loan value" message from the real `ProblemDetail`);
do not fake a success path or skip the screen. This becomes live functionality the moment the
backend gap closes, with no portal-side change needed.

**No idempotency protection on policy loan origination, loan repayment, or policy endorsements.**
Verified directly in `PolicyLoanController`/`PolicyLoanApiImpl` (`Idempotency-Key` accepted,
never read) and `PolicyController.applyEndorsement`. A network-timeout retry on any of these three
could create a duplicate loan, duplicate repayment, or duplicate endorsement — real financial
duplicates, not cosmetic ones. Because the backend offers nothing here, **the portal supplies a
two-layer hard guard, and a disabled button is explicitly not sufficient for either layer.**

*Layer 1 — client-side, a synchronous ref, not React state.* A `disabled` attribute driven by
`useState`/`isPending` is set asynchronously: React batches the update, so two clicks (or a click
plus an Enter keypress) dispatched in the same tick both pass the check before either re-render
lands. The guard is therefore a `useRef<boolean>` checked and set **synchronously at the top of the
submit handler, before any `await`**:

```ts
const inFlight = useRef(false);
async function onSubmit(values: LoanRequest) {
  if (inFlight.current) return;       // synchronous — no batching window
  inFlight.current = true;
  try { await submitLoan(values); }
  finally { inFlight.current = false; }
}
```

The `disabled` prop stays, driven by TanStack Query's `isPending` (§2) — but as the *visible
affordance*, not the correctness mechanism. This layer costs nothing and stops the overwhelmingly
common case (an impatient double-click).

*Layer 2 — server-side, Redis-backed atomic dedup in the BFF.* Layer 1 dies with the tab. It does
not survive a page refresh mid-request, a browser back-then-resubmit, or the user opening the same
form in a second tab — and it is trivially bypassed by anything that isn't the portal's own UI.
Since the portal is a BFF (§2), every one of these three mutations already passes through a Route
Handler we control, which is the right place for a real check. Redis is already provisioned in
`infra/docker-compose.yml` (verified — no new infrastructure needed).

The client generates a UUID **once per form instance** (not per submit attempt) and sends it as the
`Idempotency-Key` header. The Route Handler, before forwarding to the backend, performs a single
atomic claim:

```
SET idem:{realm}:{userSub}:{operation}:{key} "in-flight" NX EX 900
```

`NX` makes claim-and-check one atomic operation — the check-then-set race that a naive
`GET`-then-`SET` would have is exactly the bug this layer exists to prevent, so it must not be
implemented as two calls. If the `SET` returns nil, this key is already claimed: return the
recorded outcome if one exists, otherwise a `409` the UI renders as "this request is already being
processed."

**The claim must be released on any outcome the user can correct, or the guard becomes a bug.**
This is the trap worth stating explicitly, because two individually-reasonable rules compose into a
broken form: "one key per form instance" plus "replay the stored outcome on a repeat key" would mean
a submission rejected for a *correctable* reason (a `422` validation error, an
`INSUFFICIENT_LOAN_VALUE` `409` from the backend) permanently poisons that form — the user fixes the
amount, resubmits, and gets their own stale rejection replayed back at them with no way forward
short of a full page reload. So:

- **Success, or any definitive backend response that actually changed state** → overwrite the value
  with the outcome, keep the TTL. A retry within the window replays rather than re-submitting. This
  is the case the layer exists for.
- **A definitive backend rejection that changed nothing** (4xx validation/business errors) → `DEL`
  the key before returning the error. Nothing happened, so nothing needs deduplicating, and the
  user must be able to correct and retry immediately.
- **An indeterminate outcome** (timeout, connection reset, 5xx — we cannot know whether the backend
  applied it) → **keep the claim** and surface an "unable to confirm, check your policy before
  retrying" state. This is the one case where blocking the retry is the correct, conservative
  answer: an un-deduplicated retry against a request that may have succeeded is exactly the
  duplicate this whole section exists to prevent.

The 15-minute TTL comfortably exceeds any realistic backend call plus user retry patience, while
still letting a genuinely new second loan request through later.

The key is namespaced by realm and token subject so one customer's key can never collide with or
replay another's — the same fail-closed-by-construction instinct M11 applied to the refdata
allowlist.

**This is a portal-side mitigation, not a fix.** It closes the practical window for users coming
through this portal; it does not make the endpoints idempotent, and it protects nothing that calls
them directly. The real fix is server-side idempotency on all three endpoints, which belongs to a
backend milestone — this design should be revisited (and Layer 2 likely deleted) once that lands.

**Idempotency IS real** on claim registration, claim settlement-approval, and billing
payment-requests (verified: real dedup registries / DB-backed keys). These may safely retry on a
network error, and they need no Layer 2 — the backend already does that job properly. Layer 1 still
applies to their forms, but purely as UX hygiene (a double-click shouldn't fire two requests even
when the second is harmless), never as a correctness requirement.

**A regulatory return, agent-hierarchy, or reinsurance screen makes no sense here** — those are
staff/regulator/back-office concepts with no customer-facing analogue; not merely deferred, they
belong to a different portal entirely.

## 7. Error handling

Every backend error is an RFC 7807 `ProblemDetail` carrying an `errorCode` property. One shared
`mapApiError(problem: ProblemDetail): UserMessage` function is the single place that turns an
`errorCode` into copy — individual screens never branch on HTTP status directly. Two deliberate
exceptions get their own explicit UI state rather than falling into the generic error path:
`CHOREOGRAPHY_NOT_IMPLEMENTED` (surrender — an explanation, not an error banner) and
`INSUFFICIENT_LOAN_VALUE` (loan origination — worth its own clear message given §6's discovery that
it currently fires on every attempt).

## 8. Testing

**Vitest + React Testing Library** for component and form logic — money formatting,
`ProblemDetail`-to-message mapping, and both guard layers from §6. The guards need tests that would
actually fail without them, not tests that merely observe a disabled button: for Layer 1, fire two
submit events in the same tick and assert the submit function was called exactly once (a
state-driven `disabled` fails this test, which is the point of writing it); for Layer 2, assert the
Route Handler issues a `SET … NX EX` and returns 409 on the nil reply, against a Redis test double —
**plus one test per release rule**: a correctable 4xx `DEL`s the key and a resubmit goes through, an
indeterminate failure does *not*, and a success replays. The correctable-4xx case is the one that
catches the un-retryable-form bug, so it is not optional. Neither layer needs the backend. The token-refresh logic from §3 is also unit-tested here — expired token
triggers a refresh, failed refresh clears the session rather than surfacing an error.

**Playwright, against the real seeded local stack from M11.** Log in as `customer.owner` through
actual Keycloak (not a mocked auth provider), then exercise the claim-evidence upload→download
round trip end to end — this is both the most valuable single E2E test to write first (it directly
re-proves M11's headline claim on every CI run) and the natural template for every other flow's E2E
coverage. `customer.other` (the user who owns nothing) is the fixture for every "this must be
denied" test case, exactly as M11 seeded it for.

## 9. Deferred / explicitly out of scope

- Agent, staff, and regulator portals — separate projects.
- Swahili localization — §2, revisit if it becomes a real requirement.
- A caveat/warning UI for the zero cash-value display — §6, rejected by explicit decision; revisit
  only if the backend gap's timeline changes.
- **Real server-side idempotency on policy loan origination, loan repayment, and policy
  endorsements** — a backend change, out of scope for a frontend milestone. §6's two-layer guard is
  the interim mitigation; this belongs on the backlog as its own backend ticket.
- Any screen for `communication`/`omnichannel` — nothing exists to build against.
- Production Keycloak/deployment configuration — this spec is dev/build-time only; `redirectUris`,
  hosting, and CDN/edge concerns are a separate, later decision.
- A live-updating notification/websocket layer — no backend event stream exists to consume.
