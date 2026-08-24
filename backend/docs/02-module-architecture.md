# Deliverable 2 — Module Architecture (Revision 2)
**Digital Life Insurance Core Platform — Tanzania (Phase 0, Artifact 2 of 8)**

> Supersedes Revision 1. This revision resolves your Phase A/B/C/D review in full. Where I've refined rather than literally applied a recommendation (B1, D4), I've explained why below — flag if you want the literal version instead.

---

## 0. Revision Log

| # | Your item | Resolution |
|---|---|---|
| A1 | Per-module Postgres schemas | **Approved and applied.** Each module owns its own schema (`party.*`, `policy.*`, etc.). |
| A2 | `policyloan` → `payment` choreography | **Applied option (c).** `policyloan` publishes `LoanDisbursementRequested`; `payment` consumes it and never appears in `policyloan`'s allowed-dependency list. Full request/confirm round trip specified in §3.5. |
| A3 | `claims` → `payment` choreography | **Confirmed purely event-driven.** Same request/confirm pattern; round trip specified in §3.7. This was already correct in the §4 dependency table in Rev 1 — the gap was that the narrative didn't show the full loop back, now fixed. |
| B1 | Loan origination race condition | **Refined, see note below** — implemented as a reserve/confirm API on `policy` rather than a raw pessimistic lock, because a lock taken during `getAvailableLoanValue()`'s transaction can't survive into `policyloan`'s separate transaction without violating module transaction autonomy. §3.4/§3.5. |
| B2 | Missing domain events | **Added:** `GracePeriodTriggered`, `SurrenderValueCalculated`, `BeneficiaryChanged`, `PolicySuspended`, plus the request/confirm event pairs needed to close A2/A3. |
| B3 | Contestability period configurability | **Applied** — sourced from `refdata` (jurisdiction-keyed), not hardcoded or product-level. §3.3. |
| B4 | Offline reconciliation SLA | **Applied** — 24-hour auto-escalation to a staff worklist. §3.6. |
| C1–C3 | Actuarial/regulatory/legal inputs | **Tracked, not blocking**, see §6. Proceeding with Deliverable 3 for all modules except `finaccounting`'s deep aggregate design, which waits on C1. |
| D1 | Audit module | **Added as module #18** (`audit`), §3.18. |
| D2 | Transactional event publishing | **Added to §2** as a mandatory convention. Kafka reconsideration logged as a non-blocking Phase 1 item. |
| D3 | Reporting DB separation | Logged as a non-blocking Phase 1/2 item in `regreporting`'s notes. |
| D4 | Additional module tests | **Applied, and refined** — see the circular-dependency note in §2; the test checks the *synchronous* call graph, not event-type references. |
| D5 | Base package | Still pending your confirmation. |

---

## 1. Module Summary (18 modules)

| # | Module | Bounded Context | Weight |
|---|---|---|---|
| 1 | `party` | Party Management | Thick |
| 2 | `product` | Product Configuration | Thick |
| 3 | `underwriting` | Underwriting & Risk Assessment | Thick |
| 4 | `policy` | Policy Administration | Thick (hub) |
| 5 | `policyloan` | Policy Loans & Cash Value | Thick |
| 6 | `billing` | Premium Billing & Collection | Thick |
| 7 | `claims` | Claims Management | Thick |
| 8 | `distribution` | Distribution & Channel Management | Thick |
| 9 | `payment` | Payments & Disbursements | Thick (ACL, event-driven only) |
| 10 | `reinsurance` | Reinsurance & Cessions | Medium |
| 11 | `finaccounting` | Financial Accounting (IFRS17) | Thick |
| 12 | `regreporting` | Regulatory Reporting (TIRA) | Medium (read-model only) |
| 13 | `communication` | Customer Communications | Medium |
| 14 | `omnichannel` | Omnichannel Access | Medium (adapter only) |
| 15 | `iam` | Identity & Access | Thin |
| 16 | `document` | Document & Content Management | Thin |
| 17 | `refdata` | Reference & Master Data | Thin |
| 18 | **`audit`** *(new)* | Audit & Compliance Trail | Thin structurally, critical for compliance |

---

## 2. Cross-Cutting Conventions

**Event envelope** (unchanged from Rev 1, now doing double duty as `audit`'s entire input contract):
```java
public record DomainEventEnvelope<T>(
    UUID eventId, String eventType, int schemaVersion,
    TenantId tenantId, Instant occurredAt, T payload
) {}
```

**Transactional event publishing (D2 — now mandatory, not optional).** Every module publishes domain events via Spring Modulith's event publication registry (or `@TransactionalEventListener(phase = AFTER_COMMIT)`), never a bare `ApplicationEventPublisher.publishEvent()` inside a still-open transaction. An event must never be observable to another module before the publishing module's own state change has durably committed — this matters most for the payment request/confirm pairs below, where a rollback-then-still-fired event would create a phantom disbursement request. Kafka is logged as a Phase 1 reconsideration if in-process event volume becomes a bottleneck; not a Phase 0 blocker.

**Request/Confirm event-pair pattern (new — this is what actually closes A2/A3).** Any module that needs money moved never calls `payment` synchronously. It publishes a `*Requested` event carrying an idempotency key; `payment` consumes it, executes against the mobile-money/bank rail, and publishes `PaymentConfirmed`/`PaymentFailed` or `DisbursementCompleted`/`DisbursementFailed` correlated back by that same key. The requesting module consumes the confirmation to close its own state transition. Applied uniformly to `billing`, `policyloan`, `claims`, `distribution`, and `policy` (surrender/maturity) — see each module's detail section. Consequence: **no module is in `payment`'s allowed-dependency list, and `payment`'s public API is read-only** (`getStatus(...)`) to everything except its own internal event listeners.

**Per-module Postgres schemas (A1, approved).** Each module gets its own schema. Tenant isolation (row-level `tenant_id` + Hibernate filter) applies within every module's tables regardless of schema — the two concerns are orthogonal, as discussed in Rev 1. Cross-schema FKs remain forbidden; references are opaque IDs resolved through the owning module's API.

**Circular-dependency policy (D4, refined).** `NoCircularDependencyTest` checks the *synchronous* `@ApplicationModule(allowedDependencies=...)` call graph only — that graph must stay a DAG (see §4). Two modules are still allowed to reference each other's **event types** (e.g., `policyloan` consuming `policy`'s `PolicySurrenderInitiated`, `policy` consuming `policyloan`'s `LoanSettledForPayout`) via Spring Modulith `@NamedInterface`-scoped `events` sub-packages, because event subscription is inherently decoupled — it doesn't create a call-stack cycle or an initialization-order dependency the way a mutual synchronous API dependency would. Only the latter is what the test needs to catch.

**Additional CI gates (D4):** alongside the existing `ModularityTests.verify()`, add `NoCrossModuleJoinTest` (fails if a JPA repository query joins across module schema boundaries) and the `NoCircularDependencyTest` described above.

**Offline reconciliation SLA (B4).** A field-captured premium receipt (via `BillingSyncApi`) starts in status `PENDING_RECONCILIATION`. If no matching `PaymentConfirmed` arrives from `payment` within 24 hours, a scheduled check marks it `RECONCILIATION_OVERDUE` and raises it to a staff worklist (surfaced via `communication` to back-office, and visible in `audit`'s trail). This value is itself a candidate `refdata` parameter (`OFFLINE_RECEIPT_SLA_HOURS`) rather than a hardcoded constant, consistent with keeping statutory/operational thresholds out of code.

---

## 3. Module Detail (changed modules only shown in full; unchanged modules from Rev 1 — `product` §3.2, `distribution` structure §3.8, `reinsurance` §3.10, `communication` §3.13, `omnichannel` §3.14, `iam` §3.15, `document` §3.16, `refdata` §3.17 — carry over as written previously, with only the event additions noted inline where relevant)

### 3.3 `underwriting` — Underwriting & Risk Assessment (updated: B3)
- **Public API:** unchanged from Rev 1.
- **Notes:** `checkContestability()` now reads the contestability period from `ReferenceDataApi.getCodes("TZ_CONTESTABILITY_MONTHS")` (jurisdiction-keyed, per B3) rather than any hardcoded or product-level value — no new module dependency required, since `underwriting → refdata` was already allowed. **[VERIFY]** the actual statutory value with legal/compliance (tracked as C3).

### 3.4 `policy` — Policy Administration (updated: B1, B2, A2-adjacent netting flow)
- **Data owned:** `policy`, `policy_account`, `endorsement`, `beneficiary`, `coverage`, `loan_value_reservation` *(new, supports B1)*
- **Public API:**
```java
public interface PolicyApi {
    PolicyNumber issuePolicy(UnderwritingCaseId decision, IssueRequest req);
    void applyEndorsement(PolicyNumber policy, EndorsementRequest req);
    void surrenderPolicy(PolicyNumber policy, SurrenderRequest req);
    void changeBeneficiary(PolicyNumber policy, BeneficiaryChangeRequest req);
    Money quoteSurrenderValue(PolicyNumber policy); // triggers SurrenderValueCalculated
    PolicyView getPolicy(PolicyNumber policy);
    CoverageStatus getCoverageStatus(PolicyNumber policy, LocalDate asOf);

    // B1 fix: reserve/confirm replaces the old read-then-write race
    ReservationId reserveLoanValue(PolicyNumber policy, Money amount, Duration ttl);
    void confirmReservation(ReservationId id);
    void releaseReservation(ReservationId id); // also auto-released by a TTL sweep on crash/timeout
}
```
- **Events produced:** `PolicyIssued`, `PolicyEndorsed`, `PolicyLapsed`, `PolicyReinstated`, `PolicySuspended` *(new — distinct from lapse, e.g. a group scheme temporarily suspending contributions)*, `PolicyMatured`, `BeneficiaryChanged` *(new)*, `SurrenderValueCalculated` *(new — a quote, not a commitment)*, `PolicySurrenderInitiated` *(new — intent, carries gross proceeds)*, `PolicyMaturityInitiated` *(new — same pattern for maturity)*, `PolicySurrendered` / `PolicyMatured` *(final, now carries net-of-loan proceeds)*, `SurrenderPayoutRequested` / `MaturityPayoutRequested` *(to `payment`, per the request/confirm pattern in §2)*
- **Events consumed:** `UnderwritingDecisionMade`, `PaymentConfirmed`, `LoanRepaid`, `LoanSettledForPayout` *(new, from `policyloan` — see below)*, `DisbursementCompleted`/`DisbursementFailed` *(to close out the surrender/maturity payout)*, `PolicyLapseRecommended` (from `billing`)
- **B1 — the actual race-condition fix:** `policyloan.originateLoan()` first calls `policy.reserveLoanValue(policyNumber, amount, ttl)`. This reservation is created and released *within `policy`'s own transaction and schema* — it's `policy` locking its own row, not a lock held across two modules' transactions, which is why a bare pessimistic lock (your literal Option 1) doesn't actually work here without violating module transaction autonomy. `policyloan` then persists the `Loan` row in its own transaction and calls `confirmReservation` (or `releaseReservation` on failure). A background sweep in `policy` auto-releases any reservation whose TTL expired without confirmation, covering the crash case. This is your Option 2, adapted to respect module boundaries.
- **Surrender/maturity loan-netting choreography (resolves the "who nets the outstanding loan" gap without creating a `policy ⇄ policyloan` synchronous cycle):**
  1. `policy.surrenderPolicy()` computes gross proceeds, persists an intermediate state, emits `PolicySurrenderInitiated{policyNumber, grossProceeds}`.
  2. `policyloan` (event listener, not a sync call) settles any outstanding loan against that policy and emits `LoanSettledForPayout{policyNumber, settlementAmount}` (0 if no loan).
  3. `policy` consumes `LoanSettledForPayout`, computes `net = gross − settlement`, finalizes to `Surrendered`, emits `PolicySurrendered{policyNumber, netProceeds}` and `SurrenderPayoutRequested`.
  4. `payment` disburses; `policy` and `finaccounting`/`communication` consume `DisbursementCompleted`.
  Because both directions are event subscriptions (not `allowedDependencies` service calls), §4's DAG stays intact. **Given this has real timeout/compensation needs (what if `policyloan` never replies?), I'd implement this specific flow as a Camunda process instance inside `policy`'s application layer** rather than a bare event-listener chain — this is exactly the "multi-step process" case Camunda is already in your stack for. Maturity follows the identical pattern (`PolicyMaturityInitiated` → …).

### 3.5 `policyloan` — Policy Loans & Cash Value (updated: A2, B1)
- **Public API:** unchanged shape, `originateLoan` now internally performs the reserve→persist→confirm sequence from §3.4 instead of a raw read.
- **Events produced:** `LoanOriginated`, `LoanDisbursementRequested` *(to `payment`, per A2 option (c) — replaces the old ambiguous "LoanDisbursed-as-trigger")*, `LoanDisbursed` *(now the confirmation, emitted after consuming `DisbursementCompleted`)*, `LoanRepaid`, `LoanForcedLapseTriggered`, `LoanSettledForPayout` *(new, see §3.4 choreography)*
- **Events consumed:** `PaymentConfirmed` (repayment posted), `DisbursementCompleted`/`DisbursementFailed` (loan disbursement outcome — on failure, releases the `policy`-side reservation), `PolicySurrenderInitiated`, `PolicyMaturityInitiated` (loan settlement trigger)
- **Notes:** `policyloan → payment` is **not** in the allowed-dependency table — confirmed event-only per A2. `policyloan → policy` remains the only synchronous edge (reservation calls); the surrender/maturity interaction is event-only in both directions.

### 3.6 `billing` — Premium Billing & Collection (updated: B2, B4)
- **Events produced:** `PremiumInvoiceGenerated`, `PremiumOverdue`, `GracePeriodTriggered` *(new — grace period begins)*, `GracePeriodExpiring` *(imminent lapse warning)*, `PaymentRequested` (to `payment`), `PolicyLapseRecommended`, `FieldReceiptCaptured` *(new — offline capture landed)*, `FieldReceiptReconciliationOverdue` *(new — B4 SLA breach)*
- **Events consumed:** `PolicyIssued`, `PolicyEndorsed`, `PaymentConfirmed`/`PaymentFailed`
- **Notes:** `BillingSyncApi`-captured receipts are `PENDING_RECONCILIATION` until a matching `PaymentConfirmed` arrives; the 24-hour SLA (parameterized in `refdata`) triggers `FieldReceiptReconciliationOverdue`, consumed by `communication` (staff alert) and logged by `audit`.

### 3.7 `claims` — Claims Management (updated: A3)
- **Events produced:** `ClaimRegistered`, `ClaimAssessed`, `ClaimApproved`, `ClaimRejected`, `ClaimSettlementRequested` (to `payment`, per request/confirm pattern), `ClaimSettled` *(new — final confirmation, emitted after consuming `DisbursementCompleted`)*
- **Events consumed:** `DisbursementCompleted`/`DisbursementFailed` *(new — closes the loop; on failure the claim is flagged to a staff retry worklist rather than silently retried)*
- **Notes:** confirmed purely event-driven with `payment` — `claims` is not in `payment`'s caller list, matching your A3 ask exactly.

### 3.8 `distribution` — (event loop closed, same pattern)
- **Events produced:** adds `CommissionPaid` *(new — confirmation after `DisbursementCompleted`)* alongside the existing `CommissionPayoutRequested`.
- **Events consumed:** adds `DisbursementCompleted`/`DisbursementFailed`.

### 3.9 `payment` — Payments & Disbursements (updated: A2, A3, request/confirm pattern)
- **Public API (narrowed — writes are event-only, per §2):**
```java
public interface PaymentApi {
    PaymentStatus getStatus(PaymentRequestId id);
    DisbursementStatus getStatus(DisbursementId id);
}
```
- **Events consumed:** `PaymentRequested` (billing), `LoanDisbursementRequested` (policyloan), `ClaimSettlementRequested` (claims), `CommissionPayoutRequested` (distribution), `SurrenderPayoutRequested` / `MaturityPayoutRequested` (policy) — every inbound event carries a mandatory idempotency key.
- **Events produced:** `PaymentConfirmed`, `PaymentFailed`, `DisbursementCompleted`, `DisbursementFailed` — each correlated back to the originating request's idempotency key.
- **Notes:** zero modules appear in `payment`'s allowed-dependency list (§4) — it is purely event-in, event-out plus its own external-gateway ACL. This is a stronger and simpler position than Rev 1's, and directly resolves A2/A3.

### 3.11 `finaccounting` — unchanged from Rev 1, new events wired in
- **Events consumed:** adds `BeneficiaryChanged`, `PolicySuspended`, `SurrenderValueCalculated` (quote-only, informational — does not itself trigger a posting) to the existing list.
- **Notes:** aggregate design remains blocked on C1 (actuarial cohort/grouping rules) — see §6.

### 3.18 `audit` — Audit & Compliance Trail *(new module, D1)*
- **Data owned:** `audit_log` — append-only. The application's DB role gets `INSERT`/`SELECT` only; no `UPDATE`/`DELETE` grants, enforced at the Postgres role level, not just application code. Partitioned by month (volume + retention strategy detailed in Deliverable 6).
- **Public API:**
```java
public interface AuditApi {
    List<AuditEntryView> getTrail(EntityRef entity, DateRange range);
}
```
- **Events consumed:** every `DomainEventEnvelope<?>` published by every module — a single generic listener keyed on the envelope type, not on 18 individual event classes, so `audit` doesn't need a dependency edge to every other module (it only depends on the shared envelope type). Payload is persisted as JSONB.
- **Events produced:** none.
- **Notes:** this is the option-A design you recommended — event-based rather than `@Audited`/Envers, because it fits the already-event-driven architecture and keeps the audit trail structurally separate from business tables (a requirement, not just a preference, for TIRA's tamper-evidence expectations).

---

## 4. Module Dependency Graph (synchronous, must remain a DAG)

| Module | Allowed to call (synchronous) |
|---|---|
| `party` | `document`, `refdata` |
| `product` | `refdata` |
| `underwriting` | `party`, `product`, `document`, `refdata` |
| `policy` | `underwriting`, `product`, `party`, `document`, `refdata` |
| `policyloan` | `policy`, `refdata` |
| `billing` | `policy`, `product`, `refdata` |
| `claims` | `policy`, `underwriting`, `party`, `document`, `refdata` |
| `distribution` | `party`, `product`, `refdata` |
| `payment` | `refdata` only — **no other module calls into or is called by `payment` synchronously** |
| `reinsurance` | `refdata` |
| `finaccounting` | `product`, `refdata` |
| `regreporting` | `refdata` |
| `communication` | `party`, `refdata` |
| `omnichannel` | `policy`, `billing`, `claims`, `party` |
| `audit` | *(none — pure event listener)* |
| `iam`, `document`, `refdata` | *(none)* |

Unchanged from Rev 1 otherwise. The event-only relationships (not shown here, since they're not `allowedDependencies` edges) are: `policy ⇄ policyloan` (surrender/maturity netting), `billing/policyloan/claims/distribution/policy → payment` (request/confirm), and `* → audit` / `* → communication` / `* → finaccounting` / `* → regreporting` (broad fan-in consumers).

---

## 5. Consolidated Event Catalog Additions (Rev 2 delta only — full catalog is Deliverable 5)

`GracePeriodTriggered`, `SurrenderValueCalculated`, `BeneficiaryChanged`, `PolicySuspended`, `PolicySurrenderInitiated`, `PolicyMaturityInitiated`, `LoanSettledForPayout`, `LoanDisbursementRequested`, `SurrenderPayoutRequested`, `MaturityPayoutRequested`, `ClaimSettled`, `CommissionPaid`, `FieldReceiptCaptured`, `FieldReceiptReconciliationOverdue`.

---

## 6. Tracked External Inputs (Phase C — not blocking, but gating specific downstream work)

| # | Item | Owner | Blocks |
|---|---|---|---|
| C1 | IFRS17 cohort/grouping rules, GMM/PAA switchability | Actuarial | `finaccounting`'s aggregate design in Deliverable 3 only — proceeding with all other modules |
| C2 | TIRA return catalog | Compliance/Regulatory | `regreporting`'s `ReturnType` enum stays a placeholder; doesn't block module architecture |
| C3 | Statutory time limits (contestability, grace period, surrender floor) | Legal/Compliance | `refdata` seed values only; architecture already accommodates configurability |

Proposed path forward: proceed to **Deliverable 3 (Aggregate Design)** for all 18 modules now, with `finaccounting` explicitly marked as a partial/placeholder aggregate design pending C1 — rather than blocking the whole deliverable on one external dependency.

---

## 7. Still Open

- **D5 / base package** — ~~`com.nlolo.lifeplatform` still a placeholder~~ **RESOLVED:** base groupId confirmed as `tz.co.nlolo.lifeplatform` (reverse-DNS of `nlolo.co.tz`). Every module's Java package root is now `tz.co.nlolo.lifeplatform.<module>` (e.g. `tz.co.nlolo.lifeplatform.policy.domain`).
- Confirm you're comfortable with the Camunda-orchestrated surrender/maturity netting flow proposed in §3.4, versus a simpler (but timeout-fragile) plain event-listener chain.

*Holding here per your process. On your confirmation, I'll proceed to Deliverable 3.*
