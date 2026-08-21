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

**Money stays a string end to end.** Every `Money`-shaped field in every generated type is
`{ amount: string; currencyCode: string }`, matching the wire contract exactly — this platform
never sends a float or a JSON number for money, and the client must not "helpfully" coerce one. One
shared `formatMoney(amount: string, currencyCode: string)` utility is the only place that ever
parses a money string, so a formatting bug is fixable in one file rather than hunted across every
screen.

**No i18n layer.** English-only for v1, no `next-intl`/translation-key scaffolding — the backend
itself has no locale concept (every `ProblemDetail` message is English), so a translation layer now
would translate content the backend can't yet vary anyway.

**UI and data-fetching, carried over from the original stack decision (recorded here since the
condensed design walkthrough didn't re-state them, and they're still the approved choice):**
Tailwind CSS + shadcn/ui for components — unstyled-by-default primitives suit a from-scratch domain
UI better than a heavier design system, and shadcn's copy-into-your-repo model avoids a runtime
dependency on a component library that would need its own upgrade cadence. TanStack Query for
client-side data-fetching/caching/mutation state (loading/error/retry) on top of the generated
client and the BFF's Route Handlers — it is what gives the §5 submit-guards a real, tested
`isPending` state to disable a button on, rather than hand-rolled loading flags per form.

## 3. Screens, mapped to real, currently-working endpoints only

Every screen below is scoped to an endpoint verified reachable and correct during this session's
platform-readiness review and M11. Nothing here is aspirational.

| Screen | Endpoint(s) | Notes |
|---|---|---|
| Dashboard | `GET /policies` | Policy cards: status, next-due invoice via `GET /policies/{n}/invoices/next-due` |
| Policy detail | `GET /policies/{n}`, `GET /policies/{n}/coverage-status`, `GET /policies/{n}/surrender-value` | Cash value / surrender quote rendered as-is (§5) |
| Beneficiaries | `PUT /policies/{n}/beneficiaries` | Update, not create-from-scratch — a policy always has beneficiaries from issuance |
| Endorsements | `POST /policies/{n}/endorsements` | **No server-side idempotency** (§5) |
| Billing | `GET /policies/{n}/invoices`, `POST /invoices/{id}/payment-request` | Payment-request idempotency IS enforced (§5) |
| Claims list/detail | `GET/POST /claims`, `GET /claims/{id}` | |
| Claim evidence | `POST/GET /claims/{id}/evidence`, `GET /claims/{id}/evidence/{ref}` | The download endpoint M11 built. First real proof of the whole stack. |
| Policy loan | `POST/GET /policies/{n}/loans`, `GET /loans/{id}`, `POST /loans/{id}/repayments` | **No server-side idempotency on either POST** (§5). Currently always 409s (§5) — build the screen, expect it to be inert until the backend gap is fixed |
| Reference info | `GET /reference-codes/{key}` for the 5 customer-readable keys | Informational only (§4) |

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

## 4. Reference data — display only, and only the allowed keys

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

## 5. Known backend constraints this design deliberately works around

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
could create a duplicate loan, duplicate repayment, or duplicate endorsement. **The portal supplies
its own guard**: disable the submit control immediately on click, and do not re-enable it until a
definitive response (success or error) is received — no reliance on the `Idempotency-Key` header
actually doing anything server-side for these three calls specifically.

**Idempotency IS real** on claim registration, claim settlement-approval, and billing
payment-requests (verified: real dedup registries / DB-backed keys). These three may safely use a
naive "retry on network error" strategy without an additional client-side guard.

**A regulatory return, agent-hierarchy, or reinsurance screen makes no sense here** — those are
staff/regulator/back-office concepts with no customer-facing analogue; not merely deferred, they
belong to a different portal entirely.

## 6. Error handling

Every backend error is an RFC 7807 `ProblemDetail` carrying an `errorCode` property. One shared
`mapApiError(problem: ProblemDetail): UserMessage` function is the single place that turns an
`errorCode` into copy — individual screens never branch on HTTP status directly. Two deliberate
exceptions get their own explicit UI state rather than falling into the generic error path:
`CHOREOGRAPHY_NOT_IMPLEMENTED` (surrender — an explanation, not an error banner) and
`INSUFFICIENT_LOAN_VALUE` (loan origination — worth its own clear message given §5's discovery that
it currently fires on every attempt).

## 7. Testing

**Vitest + React Testing Library** for component and form logic — money formatting, the
submit-guard behavior on the three unprotected endpoints, `ProblemDetail`-to-message mapping. No
backend needed.

**Playwright, against the real seeded local stack from M11.** Log in as `customer.owner` through
actual Keycloak (not a mocked auth provider), then exercise the claim-evidence upload→download
round trip end to end — this is both the most valuable single E2E test to write first (it directly
re-proves M11's headline claim on every CI run) and the natural template for every other flow's E2E
coverage. `customer.other` (the user who owns nothing) is the fixture for every "this must be
denied" test case, exactly as M11 seeded it for.

## 8. Deferred / explicitly out of scope

- Agent, staff, and regulator portals — separate projects.
- Swahili localization — §2, revisit if it becomes a real requirement.
- A caveat/warning UI for the zero cash-value display — §5, rejected by explicit decision; revisit
  only if the backend gap's timeline changes.
- Any screen for `communication`/`omnichannel` — nothing exists to build against.
- Production Keycloak/deployment configuration — this spec is dev/build-time only; `redirectUris`,
  hosting, and CDN/edge concerns are a separate, later decision.
- A live-updating notification/websocket layer — no backend event stream exists to consume.
