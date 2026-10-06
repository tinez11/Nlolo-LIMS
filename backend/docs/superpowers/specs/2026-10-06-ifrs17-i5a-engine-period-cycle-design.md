# IFRS 17 I5a — The Engine Period Cycle (design)

Status: approved in brainstorming on 2026-10-06. Refines spec `2026-10-05-ifrs17-design.md` §9 (I5) for its first build.
I5b, the expense allocation run (P-19, month-end step 5), follows on the same pipeline.

## Goal

Run the guide's month-end steps 6 and 7 for a period:
- **Step 6 (out):** send the IFRS 17 engine the period's actual cash flows per group.
- **Step 7 (in):** load the engine's results (P-01 … P-17) through clearing account 9160, with maker-checker.
- **Then:** reconcile each group's ledger liability to the engine's closing figures, and block the period lock on an
  unexplained difference.

## Decisions (user, 2026-10-06)

1. **Our own documented templates (Q1 a).** The platform defines the extract and the results layouts, and the actuary
   maps whatever engine they use to them. The later built-in engine writes the same format.
2. **Two builds (Q2 a).** I5a is the period cycle; I5b is P-19 expense allocation.
3. **Approval (Q3 a).** A FINANCE_APPROVER who is not the uploader approves a run. The approval records the appointed
   actuary's sign-off reference and attaches the actuary's report.
4. **Reconciliation (Q4 a).** Up to TZS 1.00 per figure per group is rounding. Any larger difference is an exception
   that blocks the period lock until a replacement run fixes it, or until a finance officer explains it and a
   FINANCE_APPROVER who is not the recorder accepts the explanation.
5. **Reinsurance held is grouped per treaty and year (Q5 a)**, separately from policy groups (IFRS 17 para 61).
6. **Architecture A.** The cycle lives in `finaccounting` (an `engine` area), and posts `ENGINE_RUN` journals with
   `engine_run_id`. Both already exist in I1's schema; the ledger guard lets ENGINE_RUN post to AUTO and MAN accounts.
   finaccounting gains `reinsurance::api`, and `policy::api` if that adds no dependency cycle.

Decided in design, open to correction:
- the extract and the results both require the period to be CLOSING;
- a replacement run is allowed only while the period is not LOCKED;
- the lock does not require an engine run to exist. Enforcing step 7 before the lock is I6's month-end checklist.

## Part 1 — The extract (step 6)

**When.** Finance requests it for a CLOSING period. It is refused while any event for that period or earlier is
unposted. Extracts are numbered per period (`2026-10 #1`, `#2`, …), and all are kept.

**Content.** One workbook with three sheets, each also downloadable as CSV:

1. **Cash flows:** one row per group × movement type × account × Dr/Cr, from the period's postings: policy groups on
   21xx and 22xx, and reinsurance groups on 1430, 1431, 1436 and 1420. These are the actuals the engine must use and
   leave out of its future cash flows (guide 5.3, no double counting).
2. **Balances:** the opening and closing balance of every 21xx and 22xx account per policy group, and of every 14xx
   account per reinsurance group.
3. **Policies:** one row per in-force policy per group, with:
   - policy number, group and model;
   - issue date and sum assured;
   - premium and frequency;
   - status;
   - VFA fund value and investment-component balances, where they apply.

**Reinsurance groups.** From I5a on, reinsurance postings carry the group key `RI-<treaty short id>-<year>` in
`ifrs17_group`. The key is set when a bordereau, a recovery or a statement posts, from the treaty. A migration
backfills the existing postings from the bordereau, recovery and statement records.

**Stored.** The file goes to the document store as a new type, `IFRS17_EXTRACT`. An `engine_extract` row keeps the
period, the number, who made it, the row counts, the document reference, and the exact list of groups extracted.

## Part 2 — The results template and validation (step 7, in)

**Template.** A workbook or CSVs, downloadable blank from the console.

- **Header:** period; the extract number it answers; the engine's own run reference (must be unique); engine name
  and version; measurement date.
- **Journal sheet:** one row per group × P-entry × account × DR/CR × amount, with an optional guide movement code and a
  note. For example: `TERM-GMM-2026-REM | P-08 | 2112 | DR | 14000000.00`.
- **Closing sheet:** one row per group.
  - Policy groups: LRC, LIC, CSM.
  - Reinsurance groups: asset for remaining coverage, asset for incurred claims, reinsurance CSM.

**Validation.** All-or-nothing, with every error listed by sheet and row.

- The period is CLOSING; the named extract exists for it; the run reference is new.
- Every group exists and is in that extract. Every policy group of the extract has a closing row.
- Every entry is one of P-01 … P-17.
- **Accounts by group kind**, taken from the guide's P entries:
  - Policy groups:
    - LRC measurement 2110–2113;
    - the cleared sub-accounts 2121, 2123, 2124 and 2132 (P-03, P-13);
    - 2143;
    - LIC 22xx;
    - revenue 4110–4150;
    - expense 5130, 5300, 5400, 5410 and 5500;
    - finance 7110 and 7120.
  - Reinsurance groups: 1410–1413, 1436, and 6110–6130.
  
  An account outside the set for the group's kind is refused, naming the group, the account and the entry.
- Amounts are positive with at most two decimals. Each group balances, so the total balances.
- The closing figures match the shape of their group.

A failed upload is kept as REJECTED, with its error list and its file. A corrected file is a new upload.

## Part 3 — Approval, posting and replacement

**Statuses:** UPLOADED → VALIDATED | REJECTED → POSTED, then REPLACED when a later run supersedes it.

**Approval.**
- A FINANCE_APPROVER who did not upload the run approves it.
- The actuary's sign-off reference is required, and so is the actuary's report, attached as the new document type
  `ACTUARIAL_REPORT`.
- Rejection takes a reason.
- The run is validated again at approval.

**Posting.** One transaction posts one ENGINE_RUN journal per group into the run's period. Each journal:
- carries the engine's lines, with the group and movement dimensions;
- carries `engine_run_id`, the approver, the actuary's reference and the report;
- counter-posts every line through 9160.

The run's 9160 movement must net to zero before the run is marked POSTED. If it does not, nothing posts.

**Replacement.**
- A run may replace a POSTED run of the same period while that period is not LOCKED.
- Approving the replacement posts a full reversal of every journal of the replaced run (each with
  `reverses_journal_id`), then posts its own journals, all in one transaction.
- The old run becomes REPLACED. Both runs are kept and linked.
- A period has at most one POSTED run at a time.

## Part 4 — Reconciliation and the lock

After posting, each group's ledger balance at the end of the period is compared with the engine's closing figures:

| Group kind | Figure | Ledger accounts |
|---|---|---|
| Policy | LRC | 21xx except 2190 |
| Policy | LIC | 22xx |
| Policy | CSM | 2112 |
| Reinsurance | Asset for remaining coverage | 1410–1413 |
| Reinsurance | Asset for incurred claims | 1420 |
| Reinsurance | CSM | 1412 |

A difference of up to TZS 1.00 is agreed as rounding. A larger difference is an exception: a finance officer records an
explanation, and a FINANCE_APPROVER who is not the recorder accepts it. Alternatively, a replacement run makes the
figures agree.

`lockPeriod` refuses while the period's POSTED run has an unaccepted exception, naming the group and the difference.
The existing 9xxx-at-zero check covers 9160.

## Part 5 — API, console, tests

**API** (`openapi-finaccounting.yaml`). The same pipeline serves the console and, later, an engine calling in.

- Extracts:
  - `POST /ifrs17/periods/{period}/extracts`
  - `GET /ifrs17/periods/{period}/extracts`
  - `GET /ifrs17/extracts/{id}`
  - `GET /ifrs17/extracts/{id}/download?format=xlsx|csv&sheet=…`
- Results template: `GET /ifrs17/results-template`
- Runs:
  - `POST /ifrs17/engine-runs` (multipart)
  - `GET /ifrs17/engine-runs?period=&status=`
  - `GET /ifrs17/engine-runs/{id}`
  - `POST /ifrs17/engine-runs/{id}/approval` (sign-off reference plus the report; FINANCE_APPROVER, never the uploader)
  - `POST /ifrs17/engine-runs/{id}/rejection` (FINANCE_APPROVER, never the uploader)
- Exceptions:
  - `POST /ifrs17/engine-runs/{id}/exceptions/{group}/explanation`
  - `POST /ifrs17/engine-runs/{id}/exceptions/{group}/acceptance`

Everything else needs FINANCE_OFFICER or ADMIN; the reads also allow FINANCE_APPROVER.

**Console.** Finance → "IFRS 17 engine", one page per period, showing:
- extracts and their downloads;
- uploaded runs with their status and validation errors;
- per group, the journal and the closing figures;
- approve and reject, for an approver who did not upload the run;
- the reconciliation table (ledger, engine, difference), with explain and accept;
- replaced and replacing runs, linked.

**Tests.**

- **Unit:**
  - template parsing;
  - every validation rule;
  - the 9160 netting;
  - the rounding allowance;
  - replacement order (reverse, then post).
- **Integration:**
  - refused before CLOSING, and with unposted events;
  - an extract against real postings, reinsurance groups included;
  - upload, reject, fix, approve and post, with 9160 at zero and dimensions on every line;
  - a replacement reversing the old run in full;
  - an exception blocking the lock until it is explained and accepted;
  - maker-checker throughout;
  - tenant isolation.
- **Contract:** the approver-only endpoints, validated against the spec.
- **e2e:** extract, upload a results file, approve as the finance approver, then see the run posted and reconciled.

**Gate.** Affected backend classes; console checks; dev DB migrations; the full e2e suite; merge and push.
