# Deliverable 4 — API Contract Specification
**Digital Life Insurance Core Platform — Tanzania (Phase 0, Artifact 4 of 8)**

> Companion OpenAPI 3.1 files (delivered alongside this document): `openapi-common.yaml`, `openapi-underwriting.yaml`, `openapi-policy.yaml`, `openapi-policyloan.yaml`, `openapi-billing.yaml`, `openapi-claims.yaml`, `openapi-product.yaml`, `openapi-party.yaml`, `openapi-distribution.yaml`, `openapi-payment.yaml`, `openapi-reinsurance.yaml`, `openapi-regreporting.yaml`, `openapi-document.yaml`, `openapi-refdata.yaml`.

---

## 1. Framing Decision: Only Channel-Facing Endpoints Get a REST Contract

This is a **modular monolith**, not microservices. The module-to-module public APIs designed in Deliverable 2 (`PolicyApi.reserveLoanValue()`, `PolicyLoanApi.originateLoan()` calling into it, etc.) are **in-process Java method calls** — they never cross an HTTP boundary and therefore don't get an OpenAPI contract. Giving every internal Java interface a REST wrapper would be over-engineering for the architecture you've chosen, and would actively work against the modular-monolith rationale (operational simplicity, single deployable, no premature network calls). Those interfaces would only need an HTTP contract if a module were later extracted into its own service — the exact scenario your engineering philosophy already anticipates with the "Kafka only if modules are later extracted" note.

What follows, therefore, is only the subset of each module's behavior that an external channel (Customer Web Portal, Mobile App, Agent/Broker Portal, Bancassurance Partner Portal, Staff Back-Office, Regulator Read-Only Portal, or `omnichannel`'s USSD/SMS translation layer) actually calls over HTTP.

**Modules with no REST surface at all, and why:**

| Module | Reason |
|---|---|
| `omnichannel` | It's a caller of other modules' APIs, not a callee — USSD/SMS session logic lives here but nothing external calls *into* this module directly. |
| `iam` | Authentication is Keycloak's own OIDC endpoints, not a contract this platform defines. |
| `audit` | Pure event listener; even its read API (`getTrail`) is exposed as a query capability inside the staff back-office and regulator surfaces conceptually, but there's no independent "audit service" a channel talks to directly in Phase 0 — revisit if a dedicated compliance-investigation UI is needed later. |
| `reinsurance` | ~~Entirely back-office/treaty-administration; deferred rather than padded out here.~~ **M8 specified and built this surface**: six staff/finance-only endpoints (treaty authoring, treaty read/list, cession and recovery listing, recovery confirmation). See `api/openapi/openapi-reinsurance.yaml`. |
| `communication` | Triggered exclusively by domain events; no channel calls "send a notification" directly. A staff-facing dispatch-history view could be added later if support teams need to check delivery status per customer — not specified now since it wasn't asked for. |
| `finaccounting` | ~~Internal-only in Phase 0 (staff never call it directly; it's consumed by `regreporting`) and its aggregate design itself is still gated on C1 — an OpenAPI contract here would be built on a foundation that's explicitly not final.~~ **M9 specified and built this surface**: three staff/finance-only, read-only endpoints (`GET /chart-of-accounts`, `GET /gl-postings`, `GET /gl-postings/{journalEntryId}`), all gated `FINANCE_OFFICER`/`ADMIN` — no write endpoint exists or is planned, since every posting is derived from a domain event by the module's own listeners. See `api/openapi/openapi-finaccounting.yaml`. |

---

## 2. RFC 7807 Problem Details Standard

Every non-2xx response across every module uses `application/problem+json` with the shared `ProblemDetails` schema (`openapi-common.yaml`): `type` (a dereferenceable problem-type URI, e.g. `.../problems/beneficiary-shares-invalid`), `title`, `status`, `detail`, `instance`, plus two platform-specific extensions — `errorCode` (a stable machine-readable code for client branching, independent of HTTP status) and `traceId` (present on **every** error, including 5xx, for support/log correlation). 400 responses additionally carry an `errors[]` array of field-level violations.

Standard response set reused via `$ref` in every module spec: `400` (validation), `401` (missing/invalid token), `403` (authenticated but not authorized — see object-level authorization note below), `404` (not found — deliberately identical whether the resource doesn't exist or belongs to another tenant, to avoid leaking cross-tenant existence), `409` (optimistic-lock or state-machine conflict), `422` (semantically valid request rejected by a domain invariant).

---

## 3. Authentication & Authorization

**Four Keycloak realms** (three specified in your original prompt, plus one I've added and want your sign-off on): `customers`, `agents`, `staff`, and **`regulators`** *(new — I split this out from `staff` since TIRA's read-only portal is an external body with fundamentally different trust and audit requirements than internal back-office staff; confirm you're comfortable with a fourth realm rather than a read-only role inside `staff`)*.

**Tenant and identity never come from client input.** `tenant_id` and `party_id` are read exclusively from validated JWT claims — no endpoint accepts either as a header, query parameter, or body field that could be spoofed to access another tenant's or another party's data.

**Object-level authorization (not just role-based) is mandatory** on every customer- and agent-scoped endpoint — this is called out explicitly in `openapi-common.yaml`'s `403` response description because it's the OWASP API Top-10 #1 risk (Broken Object Level Authorization) and the single easiest thing to get wrong in a multi-tenant, multi-channel system like this one:
- A `customers`-realm token may only read/act on resources where `policyholderPartyId` / `claimantPartyId` / etc. equals the token's own `party_id` claim.
- An `agents`-realm token is scoped to policies where the agent is `agentOfRecord`, or within their agency hierarchy for supervisors.
- `staff`-realm tokens carry role claims (`UNDERWRITER`, `CLAIMS_ASSESSOR`, `CLAIMS_MANAGER`, `FINANCE_OFFICER`, `CUSTOMER_SERVICE_REP`, `ADMIN`) — note `CLAIMS_ASSESSOR` and `CLAIMS_MANAGER` are deliberately separate roles in the `claims` spec (assessment vs. settlement decision), a basic separation-of-duties control for a financial-approval workflow.

**Auth summary by module** (detail is in each spec's `security:` blocks):

| Module | customers | agents | staff | regulators |
|---|---|---|---|---|
| `party` | self-register, read own | register/assist, read scoped | full read, KYC verification | — |
| `product` | read catalog | read catalog | author + read | — |
| `underwriting` | — | submit application, read own | assess, decide, referral | — |
| `policy` | read own, endorse own (limited), surrender own, beneficiary changes | read/act scoped to book | full + manual-issue exception path | — |
| `policyloan` | originate/repay own | originate/repay scoped | read | — |
| `billing` | read own invoices | read scoped, field-receipt capture | waiver, full read | — |
| `claims` | register own, read own | register/read scoped | assess (assessor), decide (manager) | — |
| `distribution` | — | read own commission/plan | onboard, administer | — |
| `payment` | status by own reference | status scoped | full status + batches | — |
| `regreporting` | — | — | generate, read | read-only |
| `document` | read own claim evidence via the owning claim (object-level-checked); no upload endpoint | unrestricted in-tenant read, same as staff -- no agent-of-record/agency scoping exists for claim evidence today (`enforceCustomerOwnClaimOnly` is a no-op for any non-customer caller); no upload endpoint | read anything in-tenant (bare `REALM_STAFF`, no fine-grained role); no upload endpoint | — |
| `refdata` | read, allowlisted per key (a denied key 404s identically to a nonexistent one); no author/write endpoint | read, allowlisted per key (a different allowlist than customers'); no author/write endpoint | read every seeded key; no author/write endpoint | read, allowlisted (disclosed keys only); no author/write endpoint |

---

## 4. Async Operation Pattern (202 + Poll)

Two flows are genuinely long-running (the Camunda-orchestrated surrender/maturity loan-netting choreography from Deliverable 3 Rev 2 §12) and are modeled accordingly rather than pretending they're synchronous: `POST /policies/{policyNumber}/surrender` and the implicit maturity trigger both return **`202 Accepted`** with a `ProcessStatus` payload (`processInstanceId`, `status`, `resultLocation` once complete), polled via `GET /policies/{policyNumber}/processes/{processInstanceId}`. Loan origination/repayment and claim settlement follow the same shape for the same reason — they depend on the `payment` module's request/confirm event loop completing, which is not instantaneous.

---

## 5. Validation Rules Worth Calling Out Explicitly

- `Money.amount` is a decimal **string** with a 2-decimal-place pattern, never a JSON number — avoids binary floating-point error entirely at the wire-format level, not just internally.
- `Beneficiary` validation enforces exactly one of `partyId`/`freeformDesignee` per entry and that active shares sum to 100% — both are 422s, not 400s, since the request is well-formed JSON that violates a business rule, not a malformed one.
- Phone numbers in `party` registration are validated against the Tanzanian E.164 pattern (`+255` + 9 digits) at the API boundary, not left to downstream cleanup.
- `ProductVersionSpec` rejects publish (422) if `ifrsMeasurementModel` is absent, rating-factor coverage has gaps, or a fund definition is attached to a non-unit-linked product — all three are Deliverable 2/3 invariants now enforced at the contract layer, not just the aggregate layer, so a bad request never even reaches domain logic.

---

## 6. Open Items Before Deliverable 5 (Event Catalog)

1. **Confirm the fourth Keycloak realm (`regulators`)** — this is a change from your original three-realm spec; I think it's warranted but it's your call.
2. **Reinsurance and Communication REST surfaces** — currently deferred as "no new pattern to show"; say the word if you want them specified explicitly rather than implied.
3. Concrete per-claim-type `ClaimDetails` schemas (the sealed hierarchy from Deliverable 3 Rev 2) are placeholders here — real field sets land in Deliverable 6 once claim-form requirements are confirmed.

*Holding here per your process — once reviewed, I'll proceed to Deliverable 5 (Event Catalog: full schemas, versioning, producer/consumer matrix, delivery guarantees).*
