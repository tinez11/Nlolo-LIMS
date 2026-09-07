# Chart of Accounts as a First-Class Structure: Design

**Status:** approved 2026-09-05. Precedes the implementation plan.

**Goal:** Turn `finaccounting.chart_of_account` from a flat list of nine strings into a real
hierarchical accounting structure — parent/child, level, posting-allowed, status, currency,
control account, description — seeded with a full five-block chart, and give staff two ways to
read it: an expand/collapse **tree** and a searchable, filterable, sortable **table**.

**Not a new ledger.** M9 already built the posting layer this chart serves. This work gives that
layer a chart worth posting into.

---

## 1. What already exists

Established by reading the code, not assumed:

1. **The ledger works.** `journal_entry` (aggregate root, idempotency key) plus exactly two
   balanced `gl_posting` legs per entry, written by five event listeners in
   `finaccounting.application` covering eight posting paths: premium invoiced, premium collected,
   claim settled, commission paid, cession recorded, recovery confirmed, loan disbursed, loan
   repaid. `gl_posting.account_code` is a real foreign key (`fk_gl_posting_account_code`,
   finaccounting/V3) into `chart_of_account`.

2. **The chart is flat and thin.** `chart_of_account` carries `tenant_id`, `account_code`
   (`VARCHAR(20)`, PK with tenant), `name`, `account_type`, `normal_balance`, `created_at/by`,
   `updated_at/by` (V4). No parent, no level, no status, no posting flag, no currency, no
   description. RLS is on, `app_role` holds full SELECT/INSERT/UPDATE/DELETE.

3. **`account_type` and `normal_balance` are derived, not stored decisions.**
   `PostingRule.accountTypeFor` maps the leading digit (1 ASSET, 2 LIABILITY, 3 EQUITY, 4 INCOME,
   5 EXPENSE); `normalBalanceFor` gives DR to 1/5 and CR to 2/3/4. Neither is independently
   settable, by design — a stored value could contradict the code.

4. **Nine seeded accounts, all leaves, all placeholders.** `ChartOfAccountSeeder` seeds lazily per
   tenant. Its own javadoc states the production path is different: *"A real deployment does not
   call this at all: it seeds a real, Finance-approved chart of accounts per tenant during
   onboarding."*

5. **CRUD exists but is minimal.** `GET /chart-of-accounts` returns an unpaged flat array; POST
   takes code + name; PUT renames; DELETE is blocked by the FK once postings reference the
   account. All gated `FINANCE_OFFICER or ADMIN`. Account codes are validated `^[1-5]\d{3}$` at
   the wire DTO.

6. **The frontend is a flat unsorted list.** `ChartOfAccountsPage.tsx` renders one row per
   account with inline rename/delete forms. No hierarchy, no search, no filter, no sort.

7. **Latest finaccounting migration is V4.** This work is **V5**.

---

## 2. Approved decisions

Settled before design. Do not re-litigate during planning or review.

1. **Renumber and migrate** (rather than freezing old codes or bending the new scheme around
   them). Justified by finding 4 above: the nine codes are dev/test scaffolding by the seeder's
   own description, and no deployment exists. **If a pilot tenant with real postings is ever
   discovered before this ships, this decision reverts to "freeze the old codes as retired,
   non-posting accounts" and the migration becomes additive only.**

2. **The chart gains four insurance-specific accounts** the requested structure has no home for:
   Reinsurance Recoverable, Policy Loan Receivables, Unearned Premium, Reinsurance Payable. The
   requested tree is a generic company chart; this is a life insurer, and all four already
   receive postings today.

3. **`account_type` and `normal_balance` stay derived.** The new five blocks preserve the existing
   leading-digit convention exactly, so `PostingRule.accountTypeFor` is unchanged and no stored
   value can drift from the code.

4. **`posting_allowed` is enforced in the domain, not merely displayed.** A journal entry leg
   targeting a non-posting or `INACTIVE` account is rejected. Without this the flag is decoration.

5. **`control_of` ships as a documented label with no enforcement**, and this is stated rather
   than disguised. Its natural rule — "manual journal entries must not touch a control account" —
   is vacuous here, because the ledger is permanently event-only and no manual entry surface
   exists. Its real consumer is a subledger reconciliation report, which is out of scope.

6. **Account balances, trial balance and financial statements are out of scope** and are the
   recommended next piece of work.

---

## 3. The seeded chart (36 accounts)

`post` = `posting_allowed = true`. `header` = false. `←` marks the account a current code migrates
to; `✚` marks an account added beyond the requested structure.

```
1000 Assets                              L1  header
  1100 Cash and Cash Equivalents         L2  header
    1110 Main Bank Account               L3  post
    1120 Mobile Money                    L3  post   ← old 1000 Cash / Mobile Money
    1130 Petty Cash                      L3  post
  1200 Receivables                       L2  header
    1210 Premium Receivables             L3  post   ← old 1200    [control: BILLING]
    1220 Agent Receivables               L3  post              [control: DISTRIBUTION]
    1230 Other Receivables               L3  post
    1240 Reinsurance Recoverable      ✚  L3  post   ← old 1300    [control: REINSURANCE]
    1250 Policy Loan Receivables      ✚  L3  post   ← old 1400    [control: POLICYLOAN]
  1300 Investments                       L2  post
2000 Liabilities                         L1  header
  2100 Insurance Liabilities             L2  header
    2110 Claims Payable                  L3  post                 [control: CLAIMS]
    2120 Premiums Received in Advance    L3  post
    2130 Policyholder Benefits Payable   L3  post
    2140 Unearned Premium             ✚  L3  post   ← old 2200
  2200 Payables                          L2  header
    2210 Agent Commissions Payable       L3  post              [control: DISTRIBUTION]
    2220 Reinsurance Payable          ✚  L3  post   ← old 2300    [control: REINSURANCE]
  2300 Other Liabilities                 L2  post
3000 Equity                              L1  header
  3100 Share Capital                     L2  post
  3200 Retained Earnings                 L2  post
  3300 Current Year Profit/Loss          L2  post
4000 Income                              L1  header
  4100 Premium Income                    L2  post
  4200 Investment Income                 L2  post
  4300 Other Income                      L2  post
5000 Expenses                            L1  header
  5100 Claims Expense                    L2  post   ← old 5000
  5200 Commission Expense                L2  post   ← old 5100
  5300 Operating Expenses                L2  post
  5400 Other Expenses                    L2  post
  5500 Reinsurance Ceded Premium      ✚  L2  post   ← old 5200
```

**Every account remains a Finance sign-off placeholder,** exactly as M9 flagged the original nine.
No document on this platform specifies real account codes.

### 3.1 Two consequences worth stating out loud

**Two codes swap meaning.** `5100` means Commission Expense today and Claims Expense after; `5200`
means Reinsurance Ceded Premium today and Commission Expense after. Existing `gl_posting` rows
store the code, so without the remap in §5 the same historical rows would silently re-read as a
different expense line. This is the single highest-risk step in the work.

**Four codes stop being postable.** `1000`, `1200`, `2200` and `5000` currently receive postings
and become non-posting parent headers.

**`4xxx` income accounts are seeded for the first time.** M9 deliberately seeded none, because
premium is *earned* through LRC release, which is blocked on C1 (Actuarial). These accounts will
exist and sit permanently at zero — no posting rule targets them. This is a chart-completeness
change, **not** an un-deferral of IFRS 17 measurement.

---

## 4. Data model — finaccounting V5

New columns on `finaccounting.chart_of_account`:

| Column | Type | Null | Notes |
|---|---|---|---|
| `parent_code` | `VARCHAR(20)` | yes | self-FK `(tenant_id, parent_code)` → `(tenant_id, account_code)`; null only for the five roots |
| `level` | `SMALLINT NOT NULL` | no | root = 1; maintained by the domain, never client-supplied |
| `posting_allowed` | `BOOLEAN NOT NULL` | no | |
| `status` | `VARCHAR(10) NOT NULL` | no | `CHECK (status IN ('ACTIVE','INACTIVE'))`, default `ACTIVE` |
| `currency` | `CHAR(3) NOT NULL` | no | the tenant's functional currency; `TZS` throughout the seed |
| `control_of` | `VARCHAR(20)` | yes | `BILLING`, `CLAIMS`, `DISTRIBUTION`, `POLICYLOAN`, `REINSURANCE`, or null |
| `description` | `TEXT` | yes | |

`created_at`, `created_by`, `updated_at`, `updated_by` already exist (V1, V4) and are unchanged.

Index: `idx_chart_of_account_parent (tenant_id, parent_code)` — the tree read walks it. RLS policy
and grants are inherited from V2; no change needed.

---

## 5. Migration procedure (V5)

Ordered, and every step generic over `tenant_id` — no hardcoded tenant ids, matching V2's stated
reason for keeping the seed out of SQL.

**The old and new codes form a rotation, which dictates the order.** Eight of the nine legacy
codes are reused in the new chart with a different meaning, and three of them rotate:
`5000 → 5100`, `5100 → 5200`, `5200 → 5500`. There is therefore **no ordering of plain
inserts and deletes that avoids a primary-key collision**, and no ordering of updates that avoids
violating `fk_gl_posting_account_code` — the constraint must come down for the duration.

1. **Add the columns** with backfill defaults that keep the existing nine rows valid
   (`level = 1`, `posting_allowed = true`, `status = 'ACTIVE'`, `currency = 'TZS'`).
2. **Drop `fk_gl_posting_account_code`.** `gl_posting` is `PARTITION BY RANGE (created_at)`;
   dropping and recreating a foreign key on a partitioned table is supported on the Postgres 16
   this platform targets.
3. **Remap `gl_posting.account_code`** old → new from a `VALUES` mapping table, correlated per
   tenant. This is the step that must not be got wrong; see §9 for its test.
4. **Delete the nine legacy `chart_of_account` rows.** Nothing references them now.
5. **Insert the 36 new accounts** for every tenant that had a chart, parents before children so
   the self-FK holds, with `parent_code`, `level`, `posting_allowed`, `currency` and `control_of`
   all set at insert time.
6. **Recreate `fk_gl_posting_account_code`.** Its successful creation is itself the proof that
   step 3 remapped every row to a code that now exists — a migration-time assertion, not just a
   test-time one.

`ChartOfAccountSeeder` is rewritten in the same change to seed the full 36-account tree, and
`PostingRule`'s nine constants move to the new codes:

| Constant | Old | New |
|---|---|---|
| `CASH` | 1000 | **1120** |
| `PREMIUM_RECEIVABLE` | 1200 | **1210** |
| `REINSURANCE_RECOVERABLE` | 1300 | **1240** |
| `POLICY_LOAN_RECEIVABLE` | 1400 | **1250** |
| `UNEARNED_PREMIUM` | 2200 | **2140** |
| `REINSURANCE_PAYABLE` | 2300 | **2220** |
| `CLAIMS_EXPENSE` | 5000 | **5100** |
| `COMMISSION_EXPENSE` | 5100 | **5200** |
| `REINSURANCE_CEDED_PREMIUM` | 5200 | **5500** |

`normalBalanceFor` and `accountTypeFor` are untouched — the new codes keep the same leading digits.

**Dev-database note.** Per this repo's standing constraint, the dev backend and migrations are
decoupled: a green Testcontainers run does not prove the dev DB is in sync. V5 needs a manual
`psql` apply plus a server restart before real-stack e2e reflects it.

---

## 6. Domain invariants

Enforced in the domain layer, with the exception noted:

1. **An account with children can never receive postings.** Adding a child to an account that
   already has postings is rejected (`409`) rather than silently orphaning them. Adding a child to
   a childless, posting-free account flips `posting_allowed` to false.
2. **A journal entry leg targeting a non-posting or `INACTIVE` account is rejected.** The teeth
   behind decision 4.
3. **A child's code must begin with its parent's significant prefix** — the parent's code with
   trailing zeros stripped (`1200` → `12`, `5000` → `5`). This makes the numbering and the
   hierarchy incapable of *contradicting* each other, and makes cycles structurally impossible
   since a child's code always strictly extends its parent's prefix. It does not prevent *skipping*
   a level (`1110` may attach directly to `1000`); that is accepted, because a skipped level is
   merely a flat branch, not an inconsistency.
4. **A child's `level` is its parent's `level + 1`,** assigned by the domain and never accepted
   from the client.
5. **A posting's currency must match its account's `currency`.** No FX translation or revaluation.
6. **`INACTIVE` is the retirement path, not deletion.** History still resolves; new postings are
   refused. `DELETE` survives for genuinely unused accounts and now additionally requires that the
   account have no children.

---

## 7. API surface

`ChartOfAccountView` gains `parentCode`, `level`, `postingAllowed`, `status`, `currency`,
`controlOf`, `description`, `createdAt`, `createdBy`.

| Method | Path | Change |
|---|---|---|
| `GET` | `/chart-of-accounts` | unchanged shape: flat, unpaged array |
| `POST` | `/chart-of-accounts` | now accepts `parentCode`, `description`, `currency`, `postingAllowed` |
| `PUT` | `/chart-of-accounts/{code}` | now edits `name` **and** `description` |
| `POST` | `/chart-of-accounts/{code}/activate` | new |
| `POST` | `/chart-of-accounts/{code}/deactivate` | new |
| `DELETE` | `/chart-of-accounts/{code}` | unchanged path; now also blocked when the account has children |

All remain `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`.

**`GET` stays flat and unpaged deliberately.** A chart is bounded reference data — 36 rows seeded,
a few hundred at most for a real Finance-authored chart — and the existing `FinaccountingApi`
javadoc already records that reasoning. The client assembling the tree from a flat array is what
makes the Tree/Table toggle instant and keeps search and sort entirely client-side.

---

## 8. Frontend

One page, a segmented **Tree | Table** toggle persisted in the URL (`?view=tree`), defaulting to
tree.

**Tree view.** Expand/collapse per branch, lucide `Folder`/`FolderOpen` for headers and `Coins`
for postable leaves — not emoji; the console is lucide throughout. Each row shows code, name,
type, and a status badge through the existing six-bucket `StatusBadge`. Full keyboard path,
matching the console's standing requirement.

**Table view.** Debounced free-text search over code and name; filter chips for type, status and
posting-allowed; sortable columns. Reuses the existing `FilterChip` and `Panel` components rather
than introducing new ones.

Row actions keep their current inline shape (rename, activate/deactivate, delete), with create
gaining a parent selector. Tree assembly and filtering live in **pure functions** — a flat array
in, a nested structure or filtered list out — so they are unit-testable without rendering.

---

## 9. Testing

- **Domain:** each invariant in §6 gets a test, including the two rejection paths (posting to a
  header, posting to an `INACTIVE` account).
- **Migration:** a test that seeds the *old* nine accounts, writes a posting to each, runs V5, and
  asserts every posting now resolves to the correct new code and that no posting was orphaned.
  This is the highest-risk step in the work and is the test that must not be vacuous.
- **Contract:** the new activate/deactivate endpoints, the widened create/update payloads, and the
  `409` on deleting an account with children.
- **Frontend:** vitest over the pure tree-building and filtering functions; `staff-finaccounting.spec.ts`
  extended to cover both views, the toggle, expand/collapse, and search.
- **Accessible names:** this console's e2e couples tightly to accessible names, and a component
  rewrite has broken large numbers of specs before. The e2e updates are part of this work, not a
  follow-up.

---

## 10. Out of scope

Named so they are not silently assumed:

- **Reparenting an existing account.** Creating with a parent is enough; moving one later is rare
  and carries real risk to historical reporting.
- **FX translation and revaluation.** `currency` is enforced for consistency, not converted.
- **Subledger reconciliation.** The consumer that would give `control_of` teeth.
- **Manual journal entries.** The ledger stays permanently event-only; a correction is a future
  reversal entry, as M9 recorded.
- **Account balances, trial balance, financial statements.** The recommended next spec: a chart
  whose tree shows rolled-up balances is where this becomes genuinely useful to an accountant, but
  it needs period semantics and opening balances of its own.

---

## 11. Open items

1. **Finance sign-off on the whole chart.** Every code and name here is a placeholder, as they
   have been since M9. This work makes the structure real; it does not make the accounts approved.
2. **Whether `1110 Main Bank Account` should be the cash posting target instead of `1120 Mobile
   Money`.** Every money-movement path on this platform is mobile money today, so `1120` is the
   honest target and `1110`/`1130` seed as real but empty. A bank-collection path would change
   this.
3. **`CUSTOMER_SERVICE_REP`** remains undefined platform-wide and is untouched here.
