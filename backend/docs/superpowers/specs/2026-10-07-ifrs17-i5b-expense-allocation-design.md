# IFRS 17 I5b: expense allocation (P-19, month-end step 5)

Design approved 2026-10-07. Follows I5a (the engine period cycle, merged 5e7fd623).

## 1. What it is

The posting guide's P-19 moves the attributable part of the month's operating expenses (81xx-84xx, the expense pool)
into insurance service expenses and acquisition cash flows:

```
Dr 5210 Directly attributable expenses - policy maintenance (allocated)
Dr 5215 Directly attributable expenses - claims handling (allocated)
Dr 2123 Insurance acquisition cash flows        (or 5310 for PAA under the para 59(a) option)
    Cr 8490 Attributable expenses allocated to insurance contracts
```

It is step 5 of the guide's month end (5.5), before the extract (step 6): the extract must carry the month's allocated
expenses, so the engine sees actual attributable expenses next to the expected ones it released (4115).

## 2. Decisions (user answers)

1. **The amounts are three totals typed each month** (Q1 b): maintenance, claims handling and acquisition, as the
   guide's example ("expense allocation study: maintenance TZS 30m, claims handling TZS 5m, acquisition TZS 12m").
2. **The platform spreads each total across groups by a driver** (Q2 a); the approver sees the split before posting.
3. **A total above the month's pool warns but is allowed** (Q3 b): the approver acknowledges it.
4. **The extract waits for the allocation** (Q4 a): refused until the month has a posted allocation, or a posted
   "no allocation this month" record.
5. **Architecture A:** its own record inside finaccounting, posted as one `SYSTEM` journal. Not an engine run (I5a's
   one-posted-run-per-period, reconciliation and extract-exclusion rules do not fit it) and not a manual journal
   (MANUAL may not post to AUTO accounts 2123 and 5310).
6. **2123 lines are tagged `EXP_ACQ`**, a movement type added to the guide's list as a marked deviation. Not
   `IACF_*`: the guide's "Commission paid" report is IACF_* on 2123, and allocated overhead is not commission.

## 3. The flow

- **When.** Only while the month is CLOSING: event postings have stopped, so the drivers are stable. Manual journals
  (step 4 accruals) are still accepted in CLOSING, so the pool is read again at approval.
- **Prepare** (FINANCE_OFFICER): the three totals (each >= 0, at most two decimals), a study reference (required) and an
  optional note. All three at zero with a nil reason is "no allocation this month"; it is approved like any other, so
  one person alone cannot pass the extract gate.
- **While PREPARED** the allocation shows, recomputed on every read: the month's pool, and the split per group and
  category. A total above the pool shows both figures as a warning.
- **Approve** (FINANCE_APPROVER who did not prepare it): the split is recomputed and posted as one journal; the lines,
  the pool at approval and an over-pool flag are stored. Above the pool, the approval must carry `aboveThePool: true`.
  A nil allocation posts no journal but counts as decided.
- **Reject** (FINANCE_APPROVER who did not prepare it): with a reason; the preparer may prepare again.
- **Replace:** a new allocation may be prepared for a month whose allocation is POSTED, while the month is not LOCKED.
  Its approval reverses the old journal first, posts the new one, and marks the old allocation REPLACED.
- **States:** PREPARED -> POSTED | REJECTED; POSTED -> REPLACED.
- **Gates:**
  - The extract is refused until the month has a POSTED allocation (nil included): 409 `ENGINE_STATE`, "Step 5 first:
    <period> has no posted expense allocation".
  - An allocation posted after the month's latest extract makes that extract stale: the allocation page says "Extract
    #N predates this allocation; create a new extract".
  - **New rule in the I5a upload:** engine results must name the month's latest extract; naming an older one is a
    validation error ("Header: extract #1 is not the latest (#2); results answer the latest extract").
  - The period lock is blocked while an allocation is PREPARED ("Expense allocation awaiting a decision; approve or
    reject it").

## 4. The computation

- **Pool:** the month's net Dr - Cr on accounts 8100-8499, excluding 8490 and the journals of earlier allocations;
  every source counts (manual accruals, payroll, bank charges, depreciation).
- **Groups:** IFRS 17 policy groups only -- measurement model GMM, VFA or PAA. IFRS9 groups (investment contracts) and
  reinsurance groups (`RI-`) get nothing.
- **Drivers per group:**
  - Maintenance: policies in force -- the I5a `policy_snapshot` (status ACTIVE, REINSTATED, PAID_UP) with each policy's
    latest classification. In force *now*, not at the month's end: the snapshot keeps current status, not history
    (accepted limitation; a status history would lift it).
  - Claims handling: distinct claims (`reference_type = 'CLAIM'`) with 22xx postings in the month carrying the group.
  - Acquisition: policies whose classification takes effect in the month (issued then).
- **Split:** each category in proportion to its driver, rounded to cents; the remaining cents go to the groups with
  the largest fractional remainders (ties: group key order), so the lines add up exactly to the total. A category whose
  driver is zero in every group falls back to the in-force count. No IFRS 17 group at all: preparation is refused
  ("No group of insurance contracts to allocate to").
- **Acquisition account:** by the group's measurement model and the register's ACQUISITION_CASH_FLOWS election in force
  at the month's last day for that model -- GMM and VFA -> 2123; PAA -> 5310 when EXPENSE_WHEN_INCURRED, else 2123.
  1620 (pre-recognition) is never used: a group with a policy issued in the month is recognised.

## 5. Posting

One `SYSTEM` journal per approved allocation, event type `ifrs17.ExpenseAllocation`, dated the month's last day, with
`expense_allocation_id` on `journal_entry`:

- Dr 5210 per group (maintenance), Dr 5215 per group (claims handling), Dr 2123 or 5310 per group (acquisition) --
  each line with its group and measurement model; 2123 lines with movement `EXP_ACQ`, the others none.
- Cr 8490: one line, the whole total, no group.
- Zero-amount lines are not written.
- A replacement first posts `ifrs17.ExpenseAllocationReversal`: the old journal's lines with sides swapped.

The ledger guard already allows SYSTEM on AUTO and MAN accounts and in a CLOSING period; no guard change.

## 6. The extract

Cash flows gain 5210, 5215 and 5310 for policy groups (today: 21xx/22xx only). The 2123 allocation lines are already
included -- only ENGINE_RUN journals are left out.

## 7. Data (finaccounting V16)

- `expense_allocation`: id, tenant, period, status (PREPARED/POSTED/REJECTED/REPLACED), maintenance, claims_handling,
  acquisition, currency, study_reference, note, nil_reason, prepared_by/at, decided_by/at, decision_reason,
  pool_at_approval, over_pool, replaces_id, journal_entry_id, reversal_journal_id, version. Checks: decided_by <>
  prepared_by; amounts >= 0; a nil allocation (all zero) has a nil_reason. Partial unique indexes: one PREPARED and one
  POSTED per tenant and period.
- `expense_allocation_line`: allocation_id, tenant, group_key, measurement_model, category
  (MAINTENANCE/CLAIMS_HANDLING/ACQUISITION), account_code, driver, driver_count, amount.
- `journal_entry.expense_allocation_id` (nullable, FK).
- RLS tenant policies (NULLIF form) and app_role grants, as every finaccounting table.
- `MovementTypes.CODES` gains `EXP_ACQ`.

## 8. API (openapi-finaccounting.yaml)

| Method and path | Role | |
|---|---|---|
| GET `/ifrs17/periods/{period}/expense-allocation-preview?maintenance&claimsHandling&acquisition` | FINANCE | pool + split |
| POST `/ifrs17/periods/{period}/expense-allocations` | FINANCE | prepare |
| GET `/ifrs17/periods/{period}/expense-allocations` | FINANCE | the month's allocations, newest first |
| GET `/ifrs17/expense-allocations/{id}` | FINANCE | live split if PREPARED, stored lines if decided |
| POST `/ifrs17/expense-allocations/{id}/approval` `{aboveThePool}` | APPROVER | |
| POST `/ifrs17/expense-allocations/{id}/rejection` `{reason}` | APPROVER | |

Errors: 409 `ALLOCATION_STATE` (period not CLOSING; one already PREPARED; deciding your own; above the pool without
`aboveThePool`; no IFRS 17 group; replacing in a LOCKED month); 400 validation (negative, more than two decimals,
blank study reference, all zero without a nil reason); 404 `ALLOCATION_NOT_FOUND` across tenants; 403 by role.
Money as numbers, as the I5a engine views.

## 9. Console

- The IFRS 17 engine page gains an **Expense allocation (step 5)** section above Extracts: the month's allocation and
  its status; the prepare form (three totals, study reference, note) with a live preview table of the pool and the
  split per group; a "No allocation this month" option with its reason.
- "Create extract" is disabled, with the step 5 reason, until an allocation is posted.
- An allocation page (`ifrs17-engine/allocations/:id`): totals, pool, lines per group and category, the stale-extract
  notice, and Approve (with the "approve above the pool" checkbox when over) / Reject for a finance approver who did
  not prepare it.

## 10. Tests

- Pure: the split (proportions, cents to the largest remainders, ties, fallback, all-zero refusal); the acquisition
  account by model and election.
- Integration (one context): prepare/preview/approve posts the journal with the right accounts, groups and 8490
  total; over the pool needs the acknowledgement; nil; replacement reverses the old journal; the extract gate and the
  extract's new 5210/5215/5310 rows; the lock blocked by a PREPARED allocation; results naming an older extract
  rejected.
- Contract: the endpoints over HTTP (roles, 409s, 404 across tenants), OpenAPI-validated where not multipart.
- `PostingRulesTest`/`MovementTypes`: `EXP_ACQ` known.
- Console: vitest for the helpers (preview state, gates).
- e2e: the I5a engine spec records a nil allocation before extracting (its reconciliation is unchanged); a new
  `staff-ifrs17-expense-allocation.spec.ts` takes its own month before the earliest known period, prepares real totals
  (above the empty pool, so it exercises the acknowledgement), is approved by the finance approver, checks the posted
  lines, and locks the month.
