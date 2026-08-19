# M9 — Financial Accounting (IFRS 17): Design

**Status:** approved 2026-08-19. Precedes the implementation plan.

**Goal:** Build the `finaccounting` module's **double-entry GL posting layer** — a real chart of accounts, balanced journal entries derived from money-movement events, and the V2 hardening the provisional V1 schema needs — while deliberately deferring all C1-governed IFRS 17 *measurement* computation.

**Definition of Done** (the roadmap's own words, `docs/08-implementation-roadmap.md:180`): *"GL posting works, IFRS17-compliant measurement pending actuarial input."*

---

## 1. This is still an IFRS 17 module

Worth stating plainly, because "defer measurement" could be misread as "drop IFRS 17":

**Kept, and built or hardened in M9.** `finaccounting` remains the IFRS 17 context — a pure downstream consumer that "does **not** re-derive business facts; it consumes domain events... and applies actuarial/accounting transformation" (`docs/01-domain-map.md:81`). Its IFRS 17 *structure* stays in the schema and is hardened by V2 exactly like every other table: `group_of_contracts` (retaining both `measurement_model CHECK (... IN ('GMM','PAA'))` and `cohort_year`), `csm_ledger`, `lrc_ledger`, `lic_ledger`. The GL postings this milestone builds are precisely the inputs IFRS 17 measurement consumes.

**Deferred until C1 resolves.** Only the *measurement computation*: populating CSM/LRC/LIC, the CSM roll-forward, cohort assignment, and the GMM-vs-PAA choice per product line. The three measurement ledger tables ship empty, carrying an explicit `C1-BLOCKED — DO NOT POPULATE` marker in the migration.

**Why this deferral is different in kind from M7's and M8's.** M7's commission semantics and M8's cession formulas were documentation voids with no owner, so an explicitly flagged invented placeholder was the honest move. C1 is a *tracked external input with a named owner* — "IFRS17 cohort/grouping rules, GMM/PAA switchability | Actuarial" (`docs/02-module-architecture.md:197`) — and IFRS 17 measurement lands in audited financial statements. A wrong CSM roll-forward is a financial misstatement, not a fixable bug. `docs/01-domain-map.md:81` says so directly: this "is an actuarial/audit decision, not an architecture one, and materially changes this context's aggregate shape."

**The deferral is additive, not a rewrite.** When C1 lands, a later milestone assigns groups and computes measurement *on top of* postings that already exist and already carry `policy_number` and `source_ref`. Nothing built here is thrown away.

---

## 2. Context findings that shaped this design

Six facts established by reading the codebase, not assumed:

1. **`finaccounting` may call `product` and `refdata` synchronously** — `allowedDependencies = { "product::api", "refdata::api" }` (verified in `finaccounting/package-info.java`). This is *less* constrained than `reinsurance`/`distribution`, which could call only `refdata`. But it still cannot call `billing`, `claims`, `distribution`, `reinsurance`, `policyloan`, or `payment` — so every business fact must arrive by event.

2. **`gl_posting` cannot currently represent a double-entry posting.** V1's columns are `posting_id, tenant_id, group_id, period, amount, currency, posting_type, created_at`. There is **no account code and no debit/credit indicator**. A ledger without those is not a ledger.

3. **There is no chart of accounts anywhere on this platform** — no table, no `refdata` seed, nothing (grepped all of `db-migrations/refdata/`). Account codes are a smaller-scale doc void of the same kind M7/M8 met.

4. **`gl_posting.group_id` is `NOT NULL`, but "group of contracts" is the C1-governed concept.** The posting table currently requires the one thing that is blocked.

5. **`finaccounting/V1` carries the recurring V1 defect set — the 6th consecutive module.** No RLS on any of its five tables; no `GRANT` statements at all; no `version` columns; no money CHECK constraints; `lrc_ledger`/`lic_ledger` lack even `created_at`. Most consequentially, `gl_posting` has `REVOKE UPDATE, DELETE ... FROM app_role` **with no prior `GRANT`**, so `app_role` very likely cannot even `INSERT` into it — the append-only intent is there, the ability to append is not. (One thing V1 got right: `gl_posting` **is** registered with pg_partman, `p_premake => 4` from `2026-10-01` (`configure-pg-partman.sql:49-55`), so partitions self-maintain — unlike `billing.premium_invoice`'s hand-rolled ones.)

6. **Four money-movement events carry no amount, and cannot be joined synchronously.** Verified in code, not inferred:
   - `distribution.CommissionPaid` → `Map.of("statementId", …, "paidAt", …)` (`distribution/application/PaymentEventListener.java:157`)
   - `reinsurance.RecoveryConfirmed` → `Map.of("recoveryId", …, "confirmedAt", …)` (`reinsurance/application/ReinsuranceApiImpl.java:152`)
   - `policyloan.LoanDisbursed` → `Map.of("loanId", …, "disbursedAt", …)` (`policyloan/application/PolicyLoanApiImpl.java:223`)
   - (`policyloan.LoanRepaid` **does** carry `amount` — `PolicyLoanApiImpl.java:202`.)

   Each of the three amount-less events names `finaccounting` as a declared consumer, and two of their own code comments warn that a duplicate "reached finaccounting as a double journal entry" — so the producers already know finaccounting posts journal entries, while sending it nothing to post. This is a latent contract bug, not merely a gap.

---

## 3. Approved decisions

These six were adjudicated before design and are settled. Do not re-litigate during planning or review.

1. **GL posting only; defer all IFRS 17 measurement.** Per §1. The three measurement ledgers ship empty and marked.

2. **`finaccounting` owns a `chart_of_account` table**, seeded with flagged placeholders needing Finance sign-off — the same convention `refdata`'s contestability/dunning placeholders use. Owned here rather than in `refdata` because a chart of accounts is *structured* reference data (type, normal balance) that a flat key/value code-set models poorly; `finaccounting` can still call `refdata` if a code ever needs to be tenant-configurable.

3. **`gl_posting.group_id` becomes nullable**; postings carry `policy_number` and `source_ref` instead. Creating a sentinel "ungrouped" group was rejected because `group_of_contracts.measurement_model`'s CHECK forces a GMM/PAA value — smuggling in the exact decision being deferred.

4. **Post only from money-movement events** (8 posting paths, ~5 listener classes grouped by producing module), not all ~48 declared ones. `BeneficiaryChanged`, `PolicySuspended`, and `SurrenderValueCalculated` are documented as deliberately non-posting — `docs/02-module-architecture.md:145` already calls the last of these "quote-only, informational — does not itself trigger a posting."

5. **Synchronous `AFTER_COMMIT`, matching every other module.** `docs/05-event-catalog.md:62` warns that async fan-in breaks ordering and that "`finaccounting` rolling forward CSM needs `PolicyIssued` before a later `PolicyEndorsed`... processing them backwards would corrupt the roll-forward." That hazard is specific to *roll-forward*, which M9 defers; independent double-entry postings are order-insensitive. Recorded explicitly: whoever implements measurement must revisit async/ordering before enabling it.

6. **Enrich the three amount-less producer events**, following the M7 (`billing.PremiumCollected`) and M8 (`claims.ClaimSettled`) precedent. Each is additive, at a single publish site, with the value already in scope on the loaded aggregate.

---

## 4. Architecture

```
billing.PremiumInvoiceGenerated ─┐
billing.PremiumCollected         │
claims.ClaimSettled              │
distribution.CommissionPaid      ├─► event listeners ─► GlPostingCalculator ─► JournalEntry (DR+CR) ─► GlPostingRecorded
reinsurance.CessionRecorded      │                        (pure function)      2 balanced gl_posting rows
reinsurance.RecoveryConfirmed    │
policyloan.LoanDisbursed         │
policyloan.LoanRepaid           ─┘
```

One event produces one `journal_entry` (the aggregate root, carrying the idempotency key) plus exactly two `gl_posting` lines (its DR and CR legs), all in one transaction. `GlPostingRecorded` is published only when the entry was genuinely written.

Listener mechanics are the platform's established pattern, unchanged: `@TransactionalEventListener(AFTER_COMMIT)`, one reusable `PROPAGATION_REQUIRES_NEW` `TransactionTemplate` (a plain `@Transactional` from an AFTER_COMMIT callback silently joins the already-committed producer transaction and never commits — empirically confirmed on this project), `TenantContext` save/set/restore rather than an unconditional clear, and **explicit `@Component` bean names** (`finaccountingPolicyEventListener` etc.) since several modules already declare classes with these simple names.

`finaccounting/api/package-info.java` is currently **missing** `@NamedInterface("api")` — verified; the same gap `distribution` had entering M7 and `reinsurance` had entering M8. Added here.

**The pure-function split is load-bearing, not stylistic.** `GlPostingCalculator` takes an event's facts and returns a balanced `JournalEntry`; it touches no database and no Spring context. That makes every account mapping and every balance rule unit-testable without a container, and it is also what makes a future async swap (decision 5) a thin-listener change rather than a rewrite.

---

## 5. Schema — `finaccounting/V2`

`V1` is immutable (it has been applied). `V2` adds:

**Hardening the V1 defect set**
- RLS + tenant-isolation policies on all five existing tables (V1 has none anywhere)
- `GRANT USAGE ON SCHEMA` + `GRANT SELECT, INSERT, UPDATE, DELETE` to `app_role`, plus `ALTER DEFAULT PRIVILEGES`
- **`GRANT SELECT, INSERT` on `gl_posting` specifically, then re-assert the `REVOKE UPDATE, DELETE`** — restoring the append-only intent `docs/06-database-schema.md:33` requires while actually making the table writable. The ordering matters: grant first, then revoke the two verbs.
- `version BIGINT NOT NULL DEFAULT 0` on `group_of_contracts` (the one mutable aggregate root; the ledgers are append-only or C1-blocked)
- Audit columns and the missing `created_at` on `lrc_ledger`/`lic_ledger`
- `tenant_id` indexes where absent (`docs/06-database-schema.md:25` requires one on every tenant-scoped table)

**Money and domain guards**
- `amount > 0` on `gl_posting`. **Strictly positive, not `<> 0`**, and the reason is the `direction` column: in double-entry with an explicit DR/CR indicator, the direction carries the sign and the amount is always a positive magnitude. A reversal is a *new* entry with the two directions swapped, never a negative amount on the original — which is also why signed amounts would make the "every entry balances" assertion (§8) ambiguous about whether a negative DR is really a CR.
- Positive-balance CHECKs on the measurement ledgers are deliberately **not** added — those tables stay empty and their eventual sign semantics are C1's to define.

**`gl_posting` becomes a real double-entry line**
- `group_id` → nullable (decision 3)
- `+ account_code VARCHAR(20) NOT NULL` — FK to `chart_of_account`
- `+ direction VARCHAR(2) NOT NULL CHECK (direction IN ('DR','CR'))`
- `+ policy_number VARCHAR(20)` — opaque ref, never an FK (`docs/06-database-schema.md:29`)
- `+ journal_entry_id UUID NOT NULL` — groups the two legs of one entry (see the new `journal_entry` table below, which is where idempotency is actually enforced)

**New table — `journal_entry`, and it is where idempotency lives**

`gl_posting` is `PARTITION BY RANGE (created_at)` with `PRIMARY KEY (posting_id, created_at)`. **Postgres requires every unique index on a partitioned table to include all partition-key columns** — verified empirically against `postgres:16`, not assumed:

```
ERROR:  unique constraint on partitioned table must include all partitioning columns
DETAIL:  UNIQUE constraint on table "p" lacks column "created_at" which is part of the partition key.
```

So an idempotency index directly on `gl_posting` is impossible: omitting `created_at` is rejected outright, and *including* it would defeat the purpose entirely, since a redelivered event arriving at a different timestamp would satisfy the constraint and double-post.

The fix is also the better model: a **non-partitioned `journal_entry` table** is the aggregate root, and `gl_posting` rows are its lines.

- `journal_entry_id UUID PRIMARY KEY`, `tenant_id`, `source_event VARCHAR(60) NOT NULL`, `source_ref VARCHAR(100) NOT NULL`, `period VARCHAR(7) NOT NULL`, `policy_number VARCHAR(20)`, `posted_at TIMESTAMPTZ NOT NULL`, audit columns, RLS, grants
- `ux_journal_entry_once` on `(tenant_id, source_event, source_ref)` — verified working, and verified to actually reject a duplicate. This is the real backstop that makes a redelivered event a no-op; both `CommissionPaid`'s and `RecoveryConfirmed`'s own code comments name a duplicate journal entry as the feared outcome.
- `journal_entry` is append-only too: `GRANT SELECT, INSERT` then `REVOKE UPDATE, DELETE`, matching `gl_posting`.

`gl_posting` keeps `source_event`/`source_ref` as denormalised traceability columns (so a single posting row is self-describing in a query without a join), but they carry no uniqueness — the constraint lives on the parent entry.

> **Column widths are checked against their CHECK vocabularies in the same migration.** M7 shipped a CHECK admitting a 16-character value into a `VARCHAR(15)`, making a whole payout path unwritable while every test stayed green. `direction VARCHAR(2)` vs `'DR'`/`'CR'` (2) and every new enum column are verified to fit.

**New table — `chart_of_account`**

`(tenant_id, account_code)` composite PK, RLS, grants. Columns: `account_code VARCHAR(20)`, `name VARCHAR(200)`, `account_type VARCHAR(20) CHECK (... IN ('ASSET','LIABILITY','INCOME','EXPENSE','EQUITY'))`, `normal_balance VARCHAR(2) CHECK (... IN ('DR','CR'))`, audit columns. Seeded per tenant with the minimum accounts M9's eight posting paths need — `1000`, `1200`, `1300`, `1400`, `2200`, `2300`, `5000`, `5100`, `5200` — **every row commented as a placeholder pending Finance sign-off**. No `4xxx` income account is seeded, deliberately: M9 never credits income (see §6's accrual note), and seeding an account nothing posts to would imply coverage this milestone does not have.

---

## 6. The posting map

Every mapping below is **invented and flagged for Finance sign-off** — no document on this platform specifies account codes or their debit/credit treatment. Each is a balanced pair.

**Account codes follow the conventional five-block numbering scheme** (1xxx ASSET, 2xxx LIABILITY, 3xxx EQUITY, 4xxx INCOME, 5xxx EXPENSE). Using a near-universal convention rather than ad-hoc codes means Finance's eventual real chart is more likely to be a re-mapping of familiar blocks than a wholesale redesign — and the codes below live in `chart_of_account` as data, so replacing them is a seed change, not a code change.

| Event | Debit | Credit |
|---|---|---|
| `billing.PremiumInvoiceGenerated` | `1200` Premium Receivable (ASSET) | `2200` Unearned Premium (LIABILITY) |
| `billing.PremiumCollected` | `1000` Cash / Mobile Money (ASSET) | `1200` Premium Receivable (ASSET) |
| `claims.ClaimSettled` | `5000` Claims Expense (EXPENSE) | `1000` Cash / Mobile Money (ASSET) |
| `distribution.CommissionPaid` | `5100` Commission Expense (EXPENSE) | `1000` Cash / Mobile Money (ASSET) |
| `reinsurance.CessionRecorded` | `5200` Reinsurance Ceded Premium (EXPENSE) | `2300` Reinsurance Payable (LIABILITY) |
| `reinsurance.RecoveryConfirmed` | `1300` Reinsurance Recoverable (ASSET) | `5000` Claims Expense (EXPENSE) |
| `policyloan.LoanDisbursed` | `1400` Policy Loan Receivable (ASSET) | `1000` Cash / Mobile Money (ASSET) |
| `policyloan.LoanRepaid` | `1000` Cash / Mobile Money (ASSET) | `1400` Policy Loan Receivable (ASSET) |

**This is an accrual ledger, not a cash-basis one — which is why premium takes two postings, not one.** An earlier draft of this spec mapped `PremiumCollected` directly to `DR Cash / CR Premium Income`, recognising income at the moment cash arrived and never modelling the receivable at all. That is cash-basis accounting and wrong for an insurer. The obligation arises when the invoice is *generated*, so `PremiumInvoiceGenerated` raises `Premium Receivable` against `Unearned Premium`, and `PremiumCollected` then settles the receivable against cash. Neither posting touches an income account: **premium income is only earned as coverage is provided**, and that earning pattern is LRC release — C1-governed, and therefore deliberately absent from M9 (see §9). `2200 Unearned Premium` is consequently a liability that M9 only ever grows; the milestone that implements LRC release is the one that starts draining it.

`billing.PremiumInvoiceGenerated` needs **no enrichment** — verified in code at `billing/application/BillingApiImpl.java:280-282`, it already carries `invoiceId`, `policyNumber`, and `amount`.

**`policy.PolicyIssued` deliberately produces no posting, and the receivable is why.** Issuing a policy creates no cash movement and no *immediate* obligation — the obligation attaches per invoice, which is what `PremiumInvoiceGenerated` above now captures. The IFRS 17 entry that genuinely belongs at issuance is LRC/CSM initial recognition, which is C1-blocked. So nothing is lost by posting nothing here: the accrual an accountant would look for arrives one event later, from the module that actually knows the amount. Recorded so its absence reads as deliberate rather than forgotten.

**Currency:** a posting inherits its source event's currency. Postings are never converted (no FX table exists anywhere on this platform), and both legs of one journal entry always share one currency — asserted, not assumed.

---

## 7. Cross-module event enrichment

Three additive changes, each at one publish site, each with the value already in scope:

| Event | Add | Publish site |
|---|---|---|
| `distribution.CommissionPaid` | `amount` (from the statement's total) | `distribution/application/PaymentEventListener.java:157` |
| `reinsurance.RecoveryConfirmed` | `amount` (from `recovery.getRecoverableAmount()`/`getRecoverableCurrency()`) | `reinsurance/application/ReinsuranceApiImpl.java:152` |
| `policyloan.LoanDisbursed` | `amount` (from `loan.getPrincipalAmount()`/`getPrincipalCurrency()`, both already in scope) | `policyloan/application/PolicyLoanApiImpl.java:223` |

`api/asyncapi-events.yaml` and `docs/05-event-catalog.md` are updated to match, using the fully-specified decimal-string shape (`{type: string, description: "Plain-string decimal…"}`) that `ClaimSettlementRequestedPayload` established — not a bare `{type: object}`.

Each change lands inside its existing idempotency guard (all three publish only on a real, non-repeat transition), so the enrichment inherits that protection rather than reopening it.

---

## 8. REST surface and testing

**`api/openapi/openapi-finaccounting.yaml`** (new — none exists). Read-only, staff/finance-gated (`hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`) — the same decision recorded in M7 and M8, for the same reason: no finance-specific staff role exists. Endpoints: `GET /gl-postings` (filterable by period/policy), `GET /gl-postings/{journalEntryId}` (both legs), `GET /chart-of-accounts`. **No write endpoints** — postings are derived from events only, never hand-entered; that is what makes the ledger trustworthy. Money on the wire is a decimal string, never a JSON number.

**Tests**
- `GlPostingCalculatorTest` — pure, container-free: every one of the eight mappings, each asserted **balanced** (DR total = CR total), plus currency propagation and the deliberate `PolicyIssued` no-op
- A test asserting the **premium receivable round-trips to zero**: an invoice generated then collected leaves `1200 Premium Receivable` net flat (DR then CR, same amount), which is the single assertion that proves the two-posting accrual split is coherent rather than double-counting
- `JournalEntryBalanceTest` — an unbalanced entry cannot be constructed or persisted; this is the ledger's core invariant
- One end-to-end test per posting path, driving the **real** producer chain (a real premium collection, a real claim settlement, a real commission payout) against real Postgres as `app_role`, asserting real `gl_posting` rows — not hand-published payloads
- A redelivery test per path: the same event twice produces exactly one `journal_entry`, exactly two `gl_posting` rows, and one `GlPostingRecorded` (guarded by `ux_journal_entry_once`)
- A test proving the partition-key constraint is genuinely handled: assert `ux_journal_entry_once` rejects a duplicate `(tenant, source_event, source_ref)` even when the two attempts land at different `created_at` timestamps — the exact case an index on the partitioned `gl_posting` could not have caught
- `FinaccountingContractTest` — one test per reachable status, `openApi().isValid(...)` **paired with** `SpecTypeConformance.matchesDeclaredTypes(...)` on every decimal-carrying response
- `FinaccountingSpecParsesTest` — container-free spec load with `setResolve(true)`
- `finaccounting` added to `AppRolePrivilegesIntegrationTest` (**including that INSERT/SELECT succeed while UPDATE/DELETE are rejected on BOTH `gl_posting` and `journal_entry`** — the append-only guarantee, which V1's missing GRANT means has never once been verified) and `RowLevelSecurityIntegrationTest` (cross-tenant invisibility on `gl_posting`, `journal_entry`, and `chart_of_account`)

---

## 9. Flagged for sign-off, and deliberate deferrals

**Invented — needs sign-off before production use.** Every item carries a code comment saying so:
- The entire chart of accounts: every account code, name, type, and normal balance (**Finance**)
- Every posting mapping in §6, including which side each leg lands on (**Finance**)
- The decision that `PolicyIssued` posts nothing under a flat model (**Finance**, and revisited by C1)

**Blocked on C1 (Actuarial), not invented, not guessed:**
- GMM vs PAA per product line
- Cohort/grouping rules and `cohort_year`'s boundary (annual vs quarterly)
- All CSM roll-forward, LRC, and LIC computation — **including premium income recognition itself**, since earning premium as coverage is provided *is* LRC release. M9 therefore accumulates `2200 Unearned Premium` and never credits a `4xxx` income account; the milestone that implements LRC release is the one that starts recognising income and draining that liability. An accountant reading M9's output should expect a growing unearned-premium balance and zero earned premium, by design.

**Deferred deliberately, recorded so the next reader does not rediscover them:**
- The ~40 declared events that produce no posting (documented, not silently dropped)
- Async processing and per-policy ordering — revisit when measurement lands, per decision 5
- Reversal/correction postings. A posted entry is immutable (append-only, enforced by the `REVOKE`) and M9 provides no counter-entry path, so a mis-posted entry cannot yet be corrected in-ledger. The schema already supports the eventual shape — a reversal is a new journal entry with the directions swapped (§5's `amount > 0` rationale) — but no code or endpoint creates one.
- Multi-currency consolidation and any FX translation
- Period close / trial balance / period-lock semantics — nothing prevents a posting into an already-reported period

---

## 10. Acceptance criterion mapping

`docs/08-implementation-roadmap.md:180` — the milestone "cannot be finalized until C1 resolves," and names what *can* proceed: "the event-consumption plumbing, a provisional flat GL posting structure (no cohort grouping), and the module's own DDL/aggregate skeleton — all designed so that adding cohort/grouping logic later is additive, not a rewrite."

- **Event-consumption plumbing** → eight posting paths across ~5 listener classes, each with an end-to-end test driving the real producer chain
- **Provisional flat GL posting structure (no cohort grouping)** → `gl_posting` with `account_code`/`direction`/`journal_entry_id`, `group_id` nullable and unused
- **DDL/aggregate skeleton** → `V2` hardening plus `chart_of_account`; the three measurement ledgers present, empty, and marked `C1-BLOCKED`
- **Additive, not a rewrite** → postings carry `policy_number` and `source_ref`, so a later milestone can assign `group_id` and compute measurement on top of existing rows without restating them
- **Explicitly NOT claimed:** IFRS 17-compliant measurement. M9's honest DoD is GL posting only.
