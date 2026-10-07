# IFRS 17 I6: the year-end close (guide 5.7, M-07 and M-11)

Design approved 2026-10-07. Follows I5b (expense allocation). Branch `ifrs17-i6`, taken from the I5b tip.

## 1. Scope (user decision: I6 is the year-end close only)

The IFRS 17 design (2026-10-05, section 10) listed six pieces for I6. Asked whether they are necessary, the user chose
to build only the one that has no other route:

- **Built here: the year-end close.** Classes 4 to 8 close to 3310 Current year profit or loss, then to 3210 Retained
  earnings (M-07), with dividends declared closed to 3210 (M-11). 3310 is an AUTO account, so no manual journal can do
  it.
- **Not built (covered or deferred):**
  - P-18 presentation reclass -- 1510 and 2190 are MAN accounts; an I4 manual journal with "auto-reverse on" posts it.
  - Month-end checklist -- the lock already enforces the checks that matter.
  - Management reports and an IFRS 17 P&L -- the trial balance and GL postings cover the figures for now.
  - Movement reconciliation per group (paras 100-105) -- comes from the actuarial engine's output.
  - The auditors' register document -- written outside the platform.
- M-08 (statutory reserve transfer) stays a manual journal from its template; M-09/M-10 (dividends declared and paid)
  stay manual too.

## 2. Decisions (user answers)

1. **The financial year is the calendar year** (Q1 a): every tenant closes on 31 December; year 2026's close posts
   in period 2026-12.
2. **The closing entries sit in December, marked** (Q2 a): no 13th period. They are SYSTEM journals carrying
   `year_end_close_id` (not a new journal source -- the ledger guard knows four sources and SYSTEM may post to the AUTO
   3310); month views of December, the IFRS 17 extract and the expense pool leave them out.
3. **One run posts all three steps** (Q3 a): each class 4-8 account to 3310, then M-07, then M-11. M-08 stays manual.
4. **The year must otherwise be finished** (Q4 a), with the refinement agreed in design part 2: every month of the
   year before December that has postings is locked (as the lock already treats earlier months -- an empty month
   needs no closing).
5. **Architecture A:** its own record in finaccounting, prepared by one person and approved by another, as the I5b
   expense allocation.

## 3. The flow

- **Prepare** (FINANCE_OFFICER or ADMIN) the close of a year `Y`. Refused (409 `YEAR_END_STATE`) unless:
  - every month `Y-01` to `Y-11` with postings is LOCKED;
  - `Y-12` is CLOSING;
  - `Y-12` passes every other lock check now -- clearing accounts at zero, no expense allocation awaiting a decision,
    no unaccepted engine difference (the lock's own gates, run as a dry run);
  - no close of `Y` is already PREPARED.
- **While PREPARED** the close shows, recomputed on every read: each class 4-8 posting account with a non-zero balance
  for the year (net Dr - Cr over periods `Y-01`..`Y-12`, close journals excluded), the totals per class, the profit
  (credit balance of classes 4-8, positive = profit), dividends declared (net Dr on 3320 for the year), and the
  journal as it would post.
- **Approve** (FINANCE_APPROVER who did not prepare it): the preconditions are checked again, the figures recomputed,
  and one SYSTEM journal posted in `Y-12`, event `ifrs17.YearEndClose`, with `year_end_close_id`:
  1. each class 4-8 account with a balance: the opposite side for its balance, against 3310 (one 3310 leg per side,
     netted -- a single 3310 line for the year's result);
  2. M-07: 3310 to 3210 -- Dr 3310 / Cr 3210 for a profit, Dr 3210 / Cr 3310 for a loss;
  3. M-11: Dr 3210 / Cr 3320 for the dividends declared, when there are any.
  Zero amounts write no line. The record stores the totals per class, the profit, the dividends and the journal id.
- **Reject** (FINANCE_APPROVER who did not prepare it): with a reason.
- **Replace:** a close may be prepared for a year whose close is POSTED, while `Y-12` is not LOCKED. Its approval
  first posts `ifrs17.YearEndCloseReversal` (the old journal, sides swapped), marks the old close REPLACED, then posts
  the new one.
- **States:** PREPARED -> POSTED | REJECTED; POSTED -> REPLACED.
- **The lock of `Y-12`** is refused unless the year has a POSTED close that is not stale: "Year-end close required:
  prepare and approve the close of Y" / "The close of Y is stale: postings to classes 4-8 since it was approved;
  prepare a new close".
- **Stale:** a POSTED close is stale when any class 4-8 posting in the year, not from a close journal, was made after
  the close's approval (`gl_posting.created_at > decided_at`). Shown on the close and enforced at `Y-12`'s lock.
  Reaching it needs a month reopened by two people, so it is rare.

## 4. Exclusions

Journals with `year_end_close_id` are left out of:
- the IFRS 17 extract's cash flows (EngineLedger.cashFlows) -- the engine never sees closing entries;
- the expense pool (ExpenseAllocations.pool) -- 8xxx closed to 3310 is not spending;
- the stale check itself.
The trial balance and the period's postings show them: the year-end position is the closed one.
(EngineLedger.balances reads 21xx/22xx/14xx only, which the close never touches.)

## 5. Data (finaccounting V17)

- `year_end_close`: close_id, tenant_id, year (SMALLINT, 1900-2999), status (PREPARED/POSTED/REJECTED/REPLACED),
  class_totals JSONB (`{"4": n, "5": n, ...}` net Dr - Cr), profit NUMERIC(19,2), dividends NUMERIC(19,2),
  prepared_by/at, decided_by/at, decision_reason, replaces_id, journal_entry_id, reversal_journal_id, version.
  Checks: decided_by <> prepared_by. Partial unique indexes: one PREPARED and one POSTED per tenant and year.
- `journal_entry.year_end_close_id` (nullable, FK).
- RLS tenant policy (NULLIF form) and app_role grants.

## 6. API (openapi-finaccounting.yaml)

| Method and path | Role |
|---|---|
| GET `/ifrs17/year-end/{year}/preview` | FINANCE or APPROVER |
| POST `/ifrs17/year-end/{year}/closes` | FINANCE |
| GET `/ifrs17/year-end/{year}/closes` | FINANCE or APPROVER |
| GET `/ifrs17/year-end-closes/{id}` | FINANCE or APPROVER |
| POST `/ifrs17/year-end-closes/{id}/approval` | APPROVER |
| POST `/ifrs17/year-end-closes/{id}/rejection` `{reason}` | APPROVER |

`YearEndCloseView`: closeId, year, status, classTotals (per class), profit, dividends, accounts (code, name, class,
balance), lines (account, side, amount), stale, preparedBy/At, decidedBy/At, decisionReason, replacesId,
journalEntryId, reversalJournalId. The preview returns the same shape without the record's fields.
Errors: 409 `YEAR_END_STATE`; 404 `YEAR_END_NOT_FOUND`; 422 `FINACCOUNTING_VALIDATION_FAILED` (a year outside
1900-2999, a blank rejection reason); 403 by role.

## 7. Console

- **Year-end close** page (Finance menu): a year field (default last year); the preview -- accounts by class with
  their balances, totals per class, profit or loss, dividends, and the journal as it would post; "Prepare close"; the
  year's closes with their status.
- **Close page** (`year-end/closes/:closeId`): the same figures as prepared or as posted, the stale notice, Approve and
  post / Reject (with "Reason to reject") for a finance approver who did not prepare it, and "A finance approver other
  than you will approve or reject it." for the preparer.
- The periods page needs no change: the lock's refusal ("Year-end close required ...") shows on the row already.

## 8. Tests

- Pure (`YearEndCloseJournal`): from account balances -> lines; profit and loss directions for M-07; M-11 only with
  dividends; zero accounts skipped; balanced.
- Integration (one context): preconditions refused (an earlier month with postings open; December not closing;
  a pending expense allocation); prepare -> approve posts the lines (8xxx and 4xxx cleared, 3310 at zero after M-07,
  3210 moved by profit less dividends); December's lock needs the close; a later class 4-8 posting makes it stale and
  blocks the lock; a replacement reverses the old journal; close journals are not in the expense pool or the extract.
- Contract: the endpoints over HTTP.
- Console: vitest for the helpers.
- e2e: `staff-ifrs17-year-end.spec.ts` takes its own year, posts a payroll manual journal (O-01 template, two people)
  in that December while it is open, starts closing December, records no expense allocation (two people), prepares and
  approves the close (journal shows 8110, 3310, 3210), and locks December.
- `frontend/e2e/periods.ts` `untouchedPeriod` changes to December of the year before the earliest known period, so
  each spec run has a whole year to itself and no later spec lands inside a closed year.
