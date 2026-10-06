# IFRS 17 I4 — Manual Journals (DRAFT, awaiting confirmation)

> Inline execution, no subagents. Branch `ifrs17-i4` from main after I3b merges.

**Goal:** Finance and the actuary post the entries the policy system cannot. These are guide Part 4, sections M
(equity), N (investments), O (operating), Q (intermediaries) and R (reinsurance statements), plus any free-form
balanced journal on MAN or BOTH accounts. Each journal is prepared by one person and approved by another, carries a
reason and a supporting document, and is never edited once posted (spec §8, guide 2.3 and 5.4).

## What exists (I1)

- `journal_entry` already has the manual-journal columns: `source_type` MANUAL, `preparer`, `approver`, `reason`,
  `reason_code`, `document_refs`, `reverses_journal_id`, `auto_reverse_on`.
- The database already refuses:
  - a MANUAL line on an AUTO account;
  - a MANUAL line on a BOTH account without a reason code;
  - a manual journal without preparer, approver and reason, or whose approver is its preparer;
  - a line in a LOCKED period.
- A CLOSING period still accepts MANUAL and SYSTEM lines, refusing only EVENT ones. That is what month-end
  adjustments need.
- Lines carry the dimensions branch, fund, reference type and reference; a treaty or reinsurer goes in the reference.
- Missing pieces: drafts, the workflow, templates, attachments, reversal, auto-reversal, the API and the console.
  The mapping of database guard messages to HTTP 409 was deferred here from I1.

## Design

1. **Drafts in their own tables.** `manual_journal` holds the header (status, target period, reason, reason code,
   preparer, submitter, approver, decision reason, the journal_entry id once posted, reverse-on date, template id).
   `manual_journal_line` holds account, side, amount, description and the dimensions. A journal_entry is written
   only at approval, because posted journals are immutable.
   - Statuses: DRAFT → SUBMITTED → APPROVED (posted) | REJECTED (with a reason). A rejected journal can be copied
     to a new draft.
   - DRAFT is editable by its preparer only. A SUBMITTED journal can be withdrawn back to DRAFT by its preparer.
2. **Validation at submit and again at approve**, with every error listed:
   - balanced, with at least one Dr and one Cr line;
   - every account is a posting account, MAN or BOTH, and ACTIVE;
   - a BOTH account means a reason code is required;
   - one currency;
   - the target period is not LOCKED;
   - a reason, and at least one attached document.
3. **Approval** by a second person who holds the approver role (Q1), who is not the preparer, and who confirms the
   balance. One transaction posts a journal_entry with source MANUAL, preparer, approver, reason, reason code,
   document references, the policy-register version and the rules version (null). Its source event is
   `finaccounting.ManualJournal` and its source ref the draft id. A guard refusal (a period locked meanwhile) maps to
   409 with the guard's words.
4. **Reversal.** One click creates a reversal draft: lines swapped, `reverses_journal_id` set, dated in the current
   open period. It needs approval like any manual journal, and a journal can be reversed only once.
5. **Auto-reversal** (accruals, P-18 style). A journal approved with a reverse-on date (the first day of a later
   period) is reversed by an hourly drain on that date, as a SYSTEM journal, without a second approval (spec §8).
   - It is idempotent on the original journal.
   - If that period is LOCKED, the reversal waits and is shown on the console.
6. **Templates.**
   - (a) A guide library generated once from the guide's Part 4 entries (M, N, O, Q, R: about 52), shipped as
     `finaccounting/manual-journal-templates.yaml`: entry id, title, "when", lines (account, side, a blank amount,
     description). The source-of-truth file pattern matches the posting rules and is validated at startup against
     the chart's MAN/BOTH accounts.
   - (b) Saved templates (recurring: depreciation, lease interest, payroll), stored per tenant in
     `journal_template`. Each month's journal is still a draft that is approved.
7. **Documents.** A new DocumentType JOURNAL_SUPPORT, uploaded through `document::api` (a new dependency for
   finaccounting) and referenced on the draft. The posted journal keeps the references.
8. **Reason codes** for BOTH accounts are a refdata list (Q3).
9. **API.** `/finance/manual-journals` (create, update, list with status/period/preparer filters, get), `/submit`,
   `/withdraw`, `/approve`, `/reject`, `/reverse`, `/documents`; `/finance/journal-templates` (guide plus saved).
   All are in OpenAPI.
10. **Console.** Finance → Journals:
    - a list by status;
    - an editor: template picker, a lines grid with live Dr/Cr totals, account search limited to MAN/BOTH,
      dimension fields, reason and reason code, attachments;
    - an approve/reject panel on the detail page (not offered to the preparer);
    - reverse;
    - a template library page.
11. **Gate.** Affected classes, frontend, a full e2e with a new spec (draft → submit → approve by a second person →
    posted → reverse), then merge.

## Design check against the code (2026-10-06, awaiting confirmation)

- **C1 — Role.**
  - Keycloak staff realm: a new FINANCE_APPROVER realm role, and a dev user `staff.finance-approver` (FINANCE_OFFICER
    + FINANCE_APPROVER, branch DSM).
  - `scripts/apply-finance-approver.sh` adds both to the running dev Keycloak, the same pattern as
    apply-home-branch.sh.
  - Backend endpoints: `/approve` and `/reject` are gated on FINANCE_APPROVER; everything else on FINANCE_OFFICER or
    ADMIN.
  - Console: `staffRoles` gains FINANCE_APPROVER.
  - e2e: a new auth setup, `auth-finance-approver.setup.ts`.
- **C2 — Documents.**
  - document V7 adds JOURNAL_SUPPORT to the type CHECK; files go to the general bucket.
  - Upload uses claims' multipart pattern (`AllowedDocumentContentTypes`), with owner context
    `manual-journal:{id}`.
  - finaccounting gains `document::api` as an allowed dependency. The existing `/documents/{ref}` serves downloads.
- **C3 — Reason codes.** refdata V9 adds a JOURNAL_REASON list of the 7 codes. The console reads it with the
  existing `useReferenceCodes`.
- **C4 — Line upload.** POI and commons-csv are already dependencies. Policy's `XlsxToCsv` is internal to policy,
  so it moves to the shared root package, where `AllowedDocumentContentTypes` already lives. Policy's two callers
  change only an import.
  - Upload columns: account, side (DR/CR), amount, description, branch, fund, reference.
  - Every row is checked, and all errors are listed before any line is added to the draft.
- **C5 — Templates: 15 of the guide's 52 Part 4 entries post to AUTO accounts,** which the guide's own chart locks
  against manual journals. The database refuses them. Parsed 2026-10-06:
  - **M-07**, profit to retained earnings (3310): the year-end close, a SYSTEM run in I6 per spec §8.
  - **R-01, R-03, R-04**, reinsurance statements (1420, 1430, 1431, 6120): I3c's bordereau.
  - **Q-01..Q-04, Q-06, Q-09**, broker, agent and bancassurance statements (1320, 2122, 2123, 2510, 2520, 2530):
    commission and statement settlement, which is the system's.
  - **Q-07**, WHT remitted to TRA (2610); **Q-08**, unrecoverable clawback written off (1370); **O-10**, old
    unallocated receipts to unclaimed monies (2420): finance acts on AUTO accounts.
  - **Option D (recommended):** the library holds the 37 entries that are valid manual journals. The 15 are listed
    as "posted by the system", each naming where it will come from: I6, I3c, or a follow-up for Q-07/Q-08/O-10. The
    chart stays exactly the guide's, as decided in I1.
  - **Option E:** change those accounts to BOTH so finance can post them with a reason code. This weakens the
    control on 2122, 2123 and 2510-2530, and departs from the guide chart.
- **C6 — Posting.** The approved journal posts through `FinaccountingApiImpl.postEntry` with source MANUAL,
  `asManual(preparer, approver, reason, reasonCode, documentRefs)`, source event `finaccounting.ManualJournal` and
  source ref the draft id.
  - A reversal uses source ref `{id}:reversal` and `reversing(originalId)`.
  - An auto-reversal is a SYSTEM journal with source ref `{id}:auto-reversal`.
  - Database guard messages (`LEDGER_*`) map to 409 with the guard's words. This also lands I1's deferred mapping.
- **C7 — Period.** The draft's target period defaults to the current civil month. It may be any period not LOCKED;
  a CLOSING period still takes manual journals, which is where month-end adjustments go.

## User answers (2026-10-06): all recommendations accepted

- **Q1 — (a).** New Keycloak role FINANCE_APPROVER, plus a dev user `staff.finance-approver`. Only that role
  approves or rejects a manual journal, and never the preparer.
- **Q2 — yes.** The guide library (~52 Part 4 entries, a generated read-only file) plus tenant-saved recurring
  templates.
- **Q3 — confirmed.** CORRECTION, RECLASSIFICATION, ACCRUAL, WRITE_OFF, RECONCILIATION_ADJUSTMENT, MIGRATION, OTHER,
  as a refdata list.
- **Q4 — (a).** CSV/Excel upload of lines into a draft in I4.
- **Q5 — as the spec says.** The system posts the reversal on day 1 of the next period, without a second approval.

## Questions as asked

- **Q1 — Who may approve a manual journal?** The spec says "the finance-approver role".
  - (a) A new Keycloak role, FINANCE_APPROVER, for finance managers. A dev user `staff.finance-approver` would be
    seeded. This is stricter, and I recommend it: manual journals move money that no event backs.
  - (b) Any other FINANCE_OFFICER or ADMIN, as the period lock and the accounting policy register already work.
- **Q2 — Templates.** The guide's ~52 Part 4 entries ship as a read-only library, generated from the guide, plus
  user-saved recurring templates. Confirm.
- **Q3 — Reason codes for BOTH accounts.** A proposed starting list, maintained in reference data:
  CORRECTION, RECLASSIFICATION, ACCRUAL, WRITE_OFF, RECONCILIATION_ADJUSTMENT, MIGRATION, OTHER. Edit or confirm.
- **Q4 — Bulk lines.** For a large journal (O-11 IFRS 17 opening balances, payroll), allow uploading lines from
  CSV/Excel into a draft?
  - (a) Yes, in I4.
  - (b) Later.
- **Q5 — Auto-reversal timing.** A reversal on the first day of the next period, posted by the system without
  approval (the spec). Confirm, or require approval.
