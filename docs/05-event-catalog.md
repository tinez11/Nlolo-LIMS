# Deliverable 5 — Event Catalog
**Digital Life Insurance Core Platform — Tanzania (Phase 0, Artifact 5 of 8)**

> Companion file: `asyncapi-events.yaml` — a single AsyncAPI 2.6 document covering all 57 domain events accumulated across Deliverables 2 and 3, validated with the official AsyncAPI CLI (0 errors; only cosmetic `operationId`/`messageId`-style warnings remain, which don't affect validity). Chosen over a prose-only catalog because it's a real, tool-checkable contract — consistent with the OpenAPI rigor in Deliverable 4 — and because modeling it as one-channel-per-event-type means it converts to a Kafka-backed spec with no restructuring if a module is ever extracted, per your stated migration path.

---

## 1. Why AsyncAPI, and How to Read It

Every module publishes to an in-process Spring Application Event bus today (no broker). AsyncAPI's `publish` operation is used loosely here to mean "the producer module publishes to this channel" — the document describes the shared bus, not one service's inbound/outbound contract, since there's no single "application" perspective that makes sense for an in-process monolith's internal event catalog. Each channel's `description` field states producer and consumers in plain text (57 short entries) rather than modeling 18 separate subscriber applications, which would add ceremony without adding information at this stage.

Every message payload is `allOf: [EventEnvelopeMeta, <event-specific fields>]` — `EventEnvelopeMeta` is defined once and reused, directly reflecting Deliverable 2's `DomainEventEnvelope<T>` (`eventId`, `eventType`, `schemaVersion`, `tenantId`, `occurredAt`) plus one addition, `sequenceNumber` (§4 below).

---

## 2. Producer → Consumer Matrix

| Producer | Event count | Primary consumers |
|---|---|---|
| `party` | 3 | distribution, underwriting, communication, audit |
| `product` | 2 | finaccounting, audit |
| `underwriting` | 2 | policy, communication, audit |
| `policy` | 13 | billing, distribution, reinsurance, policyloan, claims, finaccounting, regreporting, communication, payment, audit |
| `policyloan` | 6 | policy, payment, finaccounting, communication, audit |
| `billing` | 9 | policy, payment, distribution, finaccounting, regreporting, communication, audit |
| `claims` | 6 | reinsurance, payment, finaccounting, communication, regreporting, audit |
| `distribution` | 5 | payment, finaccounting, communication, audit |
| `payment` | 4 | billing, policyloan, claims, distribution, policy, finaccounting, communication, audit |
| `reinsurance` | 3 | finaccounting, regreporting, audit |
| `finaccounting` | 2 | audit |
| `communication` | 2 | audit |
| `document` | 1 | audit |

`audit` and `communication` are the two broadest fan-in consumers (audit takes literally all 58; communication takes roughly two-thirds — anything customer-visible). Neither appears as a "consumer" column entry needing its own row above, per Deliverable 3's §9 clarification that this is a structural fan-in, not an oversight.

**M10 reconciliation.** Every `regreporting` consumer entry above (`policy`, `claims`, `billing`, `reinsurance` rows) is now genuinely backed by a real event listener as of this milestone — `regreporting.application.{PolicyEventListener,ClaimsEventListener,BillingEventListener,ReinsuranceEventListener}` — rather than the design-intent placeholder it had been since Deliverable 3; `regreporting` is consequently now a real fan-in consumer for `policy.PolicyIssued`/`PolicyLapsed`/`PolicyMatured`/`PolicySurrendered`/`PolicyReinstated`, `claims.ClaimRegistered`/`ClaimApproved`/`ClaimRejected`/`ClaimSettled`, `billing.PremiumCollected`, and `reinsurance.CessionRecorded`. `regreporting` was **added** to the `billing` and `reinsurance` rows above, which previously omitted it entirely despite `billing.PremiumCollected` and `reinsurance.CessionRecorded` being exactly the events `regreporting`'s `premium_movement`/`reinsurance_movement` tables are fed from.

**Correction to the `finaccounting` row.** It previously listed `regreporting` as a consumer of `finaccounting`'s two events; that was never true, and `regreporting`'s application package has no `FinaccountingEventListener` to prove otherwise. This is a deliberate deferral, not an oversight: `finaccounting.GlPostingRecorded`'s payload carries no account codes (only amounts/direction/period/policy_number at the posting grain), so the only figure a `regreporting` listener could derive from it is a coarse control total, with no way to attribute it to any reporting dimension this module's tables are keyed on — not worth a dedicated listener for that alone. `finaccounting.CsmRolledForward` remains C1-blocked with no producer at all yet, so it could not be genuinely consumed by anything.

**Corrections to the `product` and `underwriting` rows (M10 final review, I5).** Both previously listed `regreporting` as a consumer. Neither was ever true, on the same evidence as the `finaccounting` row above: `regreporting`'s application package declares exactly four listeners — `PolicyEventListener`, `ClaimsEventListener`, `BillingEventListener`, `ReinsuranceEventListener` — and not one of them handles a `product.*` or `underwriting.*` event type. `regreporting` has been removed from both rows, and the four false per-channel claims behind them (`product.ProductDefinitionPublished`, `product.ProductVersionRetired`, `underwriting.UnderwritingCaseOpened`, `underwriting.UnderwritingDecisionMade`) corrected in `api/asyncapi-events.yaml` with the reason each is a non-need rather than a gap. Neither is a deferral: this module's fact tables key on an **opaque** `product_id` and never need a product definition, and every measure it records is a policy/claim/premium/cession movement — an underwriting case is none of those, and a declined case never becomes a policy at all, so counting it would overstate new business. The `policy` and `claims` rows correctly **keep** `regreporting`, being real for five and four of their events respectively; the two events within them that `regreporting` genuinely does *not* consume (`policy.PolicyEndorsed`, `claims.ClaimAssessed`) are corrected at channel level in `api/asyncapi-events.yaml` rather than by dropping a row-level entry that is otherwise true. Of those, `policy.PolicyEndorsed` is the one genuine deferral in the set — an endorsement can revise a sum assured, so `SUM_ASSURED_IN_FORCE` reflects sums assured **as issued**, not as endorsed; see that channel's own description for why an "adjustment" movement cause is not invented ahead of C2.

---

## 3. Versioning Policy

- Every message starts at `schemaVersion: 1`.
- **Additive, backward-compatible changes** (a new optional/nullable field) do **not** bump the version — existing consumers ignoring an unknown field is expected and safe.
- **Breaking changes** (removing a field, changing a field's type or semantic meaning, renaming) require incrementing `schemaVersion` and publishing **both** the old and new version side-by-side for a defined migration window (proposed: two release cycles) so consumers migrate on their own schedule rather than being forced to upgrade in lockstep with the producer. This mirrors the versioned-immutable-snapshot pattern already used for `ProductVersion` in Deliverable 3 — the same discipline, applied to events instead of product configuration.
- `eventType` is the fully-qualified channel name (e.g. `policy.PolicyIssued`) and never changes across versions — version is carried in the `schemaVersion` field, not the type name, so consumers filter/branch on one field rather than parsing a version suffix out of a string.

---

## 4. Delivery Guarantees

**At-least-once, not exactly-once — stated plainly rather than over-promised.** Delivery is backed by Spring Modulith's Event Publication Registry (mandatory `@TransactionalEventListener`/registry-tracked publication per Deliverable 2 Rev 2 §2): an event is only externally observable after the producer's own transaction commits, and a registry entry is marked complete only once every registered listener has successfully processed it. Anything left incomplete (crash mid-processing, listener exception) is retried. This guarantees the event is delivered *at least* once — it does **not** guarantee exactly once, and true exactly-once isn't achievable anyway once the flow crosses into an external mobile money gateway that has no concept of your internal transaction boundary.

**Consequence: every consumer must be idempotent.** Three concrete mechanisms are used, chosen per consumer rather than applied uniformly:
1. **Natural idempotency via aggregate invariants** — e.g., a `policy` listener re-processing an already-applied `PaymentConfirmed` is a no-op because the invoice is already `PAID`; the state machine check makes reprocessing safe without extra bookkeeping.
2. **Unique DB constraint on a business key** — `payment`'s `idempotencyKey` uniqueness (Deliverable 3 §7.3) means a replayed `LoanDisbursementRequested` simply fails the insert rather than double-disbursing.
3. **Explicit processed-event dedup table** — required specifically for consumers whose side effect is externally irreversible and *not* naturally idempotent: `communication` (a resent SMS is a real, user-visible duplicate) tracks processed `eventId`s and skips reprocessing rather than relying on the producer never redelivering.

**Retry and dead-lettering:** exponential backoff on listener failure, capped retry count, then the event lands in a `failed_event` table (not silently dropped) surfaced to a staff monitoring view — this is the same 24-hour-escalation *shape* as the offline-receipt SLA (Deliverable 2 Rev 2 §2) and the stuck-Camunda-process check (Deliverable 3 Rev 2 §12), applied a third time here for consistency: don't let a failure mode go silent anywhere in the platform.

---

## 5. Ordering

Spring's default in-process event dispatch preserves publish order for **synchronous** listeners on a single thread. It does **not** guarantee order across `@Async` listeners running on a thread pool — which matters here because the broad fan-in consumers (`finaccounting`, `audit`, `regreporting`) are exactly the ones likely to run asynchronously for throughput reasons, and are also the ones where order correctness actually matters (`finaccounting` rolling forward CSM needs `PolicyIssued` before a later `PolicyEndorsed` for the same policy to be processed in that order; processing them backwards would corrupt the roll-forward).

**Fix: `sequenceNumber`** (new in this deliverable, added to `EventEnvelopeMeta`) — a monotonic counter per aggregate (e.g. per `policyNumber`), assigned by the producer within the same transaction as the state change. Order-sensitive async consumers buffer briefly and reorder by `sequenceNumber` before applying; consumers that don't care about strict order (`communication` — a slightly late SMS doesn't matter) can ignore it entirely. This is a lightweight alternative to a full per-aggregate single-threaded consumer partition, appropriate for an in-process bus; revisit if/when a module is extracted onto Kafka, where partition-key-based ordering would replace this field's role.

---

## 6. Notable Choreographies (cross-referencing prior deliverables, not new design)

- **Surrender/maturity loan-netting** (Deliverable 3 Rev 2 §12): `PolicySurrenderInitiated`/`PolicyMaturityInitiated` → `LoanSettledForPayout` → `PolicySurrendered`/`PolicyMatured` + `SurrenderPayoutRequested`/`MaturityPayoutRequested`. Camunda-orchestrated given the timeout/compensation needs already documented there.
- **Request/confirm pattern** (Deliverable 2 Rev 2 §2): `PaymentRequested`/`LoanDisbursementRequested`/`ClaimSettlementRequested`/`CommissionPayoutRequested`/`SurrenderPayoutRequested`/`MaturityPayoutRequested` → `payment` → `PaymentConfirmed`/`PaymentFailed`/`DisbursementCompleted`/`DisbursementFailed`, closed by each originating module's own confirmation event (`LoanDisbursed`, `ClaimSettled`, `CommissionPaid`). M9 Task 7: `LoanDisbursed`, `CommissionPaid` and reinsurance's `RecoveryConfirmed` were each enriched with an `amount` field so that `finaccounting` -- already a declared consumer of all three -- can post a journal entry from each (`policyloan.LoanDisbursed`: DR `1400`/CR `1000`; `distribution.CommissionPaid`: DR `5100`/CR `1000`; `reinsurance.RecoveryConfirmed`: DR `1300`/CR `5000`).
  **`policyloan.LoanRepaid` was additionally enriched with `loanTransactionId` by M9's final whole-branch review fix wave, and that field is an idempotency key, not a convenience.** Unlike the three confirmation events above, `LoanRepaid` is published *once per repayment* and a loan accepts repeatable **partial** repayments, so its `loanId` is not unique per event. `finaccounting` originally keyed its journal-entry idempotency check on `loanId` and consequently discarded every partial repayment after the first — silently, with the trial balance still balancing and `1400 Policy Loan Receivable` never clearing. `loanTransactionId` (the repayment's own `policyloan.loan_transaction` id) is unique per repayment yet stable across a genuine redelivery of that repayment, which is what an idempotency key on a repeatable event has to be. The general rule this illustrates: **a consumer's idempotency key must be unique per event occurrence, not per aggregate** — safe for the once-per-aggregate confirmation events above, wrong for any event a producer may legitimately emit more than once.

---

## 7. Open Items Before Deliverable 6 (Database Schema)

1. ~~`finaccounting`'s two events (`GlPostingRecorded`, `CsmRolledForward`) remain schematically provisional pending C1, consistent with every prior deliverable's treatment of that module.~~ Superseded by M9: `GlPostingRecorded` is real as of Task 5 (double-entry GL posting, one event per journal entry) and is no longer provisional. `CsmRolledForward` remains provisional -- IFRS 17 *measurement* (as opposed to GL posting mechanics) is still gated on C1.
2. The `failed_event` dead-letter table and staff monitoring surface (§4) need a concrete schema and, likely, a Deliverable 7 (Infrastructure) observability tie-in — flagging now so it isn't lost between deliverables.
3. Confirm the two-release-cycle migration window (§3) is the right default, or if you'd prefer a fixed calendar duration instead.

*Holding here per your process — once reviewed, I'll proceed to Deliverable 6 (Database Schema: PostgreSQL DDL per module, indexing strategy, partitioning for high-volume tables).*
