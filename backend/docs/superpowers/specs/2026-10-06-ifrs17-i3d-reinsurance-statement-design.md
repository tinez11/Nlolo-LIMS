# IFRS 17 I3d — The Reinsurance Statement (design)

Status: approved in brainstorming 2026-10-06. Follows I3c (monthly bordereau) and I4 (manual journals, FINANCE_APPROVER).
The user's I3c answer Q6 scheduled it "right after I4".

## Goal

Settle each reinsurance treaty quarterly. A statement clears what the platform has already posted to the AUTO accounts
(1430 premiums payable, 1431 commission receivable, 1420 recoveries) into the reinsurer current account 1434. It also
records funds withheld and profit commission. These are the posting guide's R-01, R-04 and R-03.

## Decisions (user, 2026-10-06)

1. **Figures from the platform (a).** A statement totals the treaty's own bordereaux and recoveries for the quarter.
   Finance adds only what the reinsurer's statement states: funds withheld and profit commission. A difference the
   reinsurer disputes is settled afterwards by an I4 manual journal on 1434 (MAN).
2. **Calendar quarters for every treaty (a).** Profit commission is entered on whichever statement carries it,
   usually Q4, and is zero otherwise.
3. **Funds withheld entered per statement (a).** It runs from 0 up to the quarter's premium payable. A later release
   (Dr 2550 / Cr 1434) is an I4 manual journal.
4. **Architecture A.** The statement lives in `reinsurance`. Approval publishes `reinsurance.StatementApproved`, and
   `posting-rules.yaml` (v4) posts it as a SYSTEM journal.

## What exists

- **I3c posts:**
  - each monthly bordereau (K-01, K-02): Dr 1436 / Cr 1430 premium, and Dr 1431 / Cr 1436 commission, reference
    BORDEREAU;
  - each recovery at claim approval (B-05): Dr 1420 / Cr 6120, reference RECOVERY.
- **Bordereaux are final once written:** `reinsurance.bordereau`, one per treaty and month.
- **Account modes:** 1420, 1430 and 1431 are AUTO; 1433, 1434 and 2550 are MAN; 6120 is AUTO. A SYSTEM journal may
  post to both AUTO and MAN accounts. An EVENT journal may not post to MAN, and a MANUAL journal may not post to AUTO.
- **Posting engine:** it posts SYSTEM today only for the PAA earning event, through a hard-coded check, and it skips
  zero-amount lines.

## The statement

**Tables.** `reinsurance.statement` holds one row per statement:
- tenant, treaty, quarter (`YYYY-Qn`), currency, status;
- premium, commission and recoveries, from the platform;
- funds withheld and profit commission, entered;
- reason, document refs, preparer, submitted at, decided by, decided at, decision reason;
- version.

`reinsurance.statement_item` lists what a statement includes: a bordereau id or a recovery id. A unique index allows
each bordereau and each recovery on at most one statement that is not REJECTED.

**Rules:**
- One live (not REJECTED) statement per treaty and quarter.
- **Preparing** (FINANCE_OFFICER or ADMIN) is allowed once the quarter has ended in Dar es Salaam. It also needs a
  bordereau written for every month of the quarter that falls inside the treaty's effective dates. Otherwise it is
  refused, naming the missing month.
- **The figures** are the sum of those bordereaux' premium and commission, plus the treaty's recoveries recorded in
  the quarter (civil dates) that are not legacy.
- **Workflow:** DRAFT → SUBMITTED → APPROVED | REJECTED (with a reason). The preparer edits a DRAFT and may withdraw
  it from SUBMITTED. Only a FINANCE_APPROVER who is not the preparer approves or rejects, and the approval checks the
  statement again first. A REJECTED statement frees its items, so the quarter can be prepared again.
- **Submitting needs:** a reason; at least one document (the reinsurer's statement); funds withheld between 0 and the
  premium; profit commission of 0 or more.

**Legacy recoveries.** A migration marks recoveries recorded before I3c as `legacy`. Those never posted to 1420
(I3c, Q4), and no statement includes them. Production has none.

## Posting

On approval, `reinsurance.StatementApproved` carries the statement id, treaty, quarter, currency, P (premium),
C (commission), R (recoveries), W (withheld) and PC (profit commission). Rules v4 add one rule, `R-STMT`, marked
`source: SYSTEM`. The engine reads that flag in place of the hard-coded PAA check, and the PAA rule gains the flag too.

The rule posts one journal in the civil month of approval, with reference type STATEMENT and reference the statement
id:

| Entry | Dr | Cr | Amount |
|---|---|---|---|
| R-04 | 1430 | 2550 | W |
| R-01 | 1430 | | P − W |
| R-01 | | 1431 | C |
| R-01 | | 1420 | R |
| R-01 | 1434 | | max(R + C − (P − W), 0): the reinsurer owes us |
| R-01 | | 1434 | max((P − W) − R − C, 0): we owe the reinsurer |
| R-03 | 1433 | 6120 | PC |

Zero lines are skipped. The journal balances by construction. Over settled quarters, 1430, 1431 and 1420 return to
zero for the treaty.

R-02 (cash on 1434) and R-05 (FX on 1434) stay I4 manual journals.

## Documents

A new DocumentType, `REINSURANCE_STATEMENT` (document V8), added to the type CHECK. The owner is `statement:{id}`,
46 characters (`owner_context` is VARCHAR(50); I4's 51-character owner failed every upload). Upload follows I4's
order: check the statement is editable by this person, then store the file. `reinsurance` gains `document::api` as an
allowed dependency.

## API

- `GET  /reinsurance/statements`: filter by status and treaty.
- `POST /reinsurance/treaties/{id}/statements`: body `{quarter}`; prepares a DRAFT.
- `GET  /reinsurance/statements/{id}`: includes the items and the journal preview (the lines above).
- `PUT  /reinsurance/statements/{id}`: funds withheld, profit commission, reason.
- `POST /reinsurance/statements/{id}/documents`: multipart.
- `POST /reinsurance/statements/{id}/submission`, `/withdrawal`: FINANCE_OFFICER or ADMIN, preparer only.
- `POST /reinsurance/statements/{id}/approval`, `/rejection`: FINANCE_APPROVER, never the preparer.

All of these are in `openapi-reinsurance.yaml`.

## Console

- **Treaty page:** a "Statements" panel listing the treaty's quarters and their statements, with "Prepare statement"
  for each ended quarter that has none.
- **Statement page:**
  - the platform's figures and items (bordereaux by month, recoveries by claim);
  - the editable fields;
  - documents;
  - the journal preview with the 1434 balance and which side it falls on;
  - approve or reject for a finance approver who is not the preparer;
  - "Posted as: journal entry" once approved.
- **Finance → "Reinsurance statements":** a list by status, so approvers can find what is waiting.

## Errors

- 422: a validation failure, listing every problem.
- 409: a refused state step, or a quarter not yet settleable (missing bordereau, quarter not ended, a live statement
  already exists).
- 404 across tenants.

## Testing

- **Unit:** the netting in both directions; W above P refused; quarter parsing and the "has ended" check; the
  months required by the treaty's effective dates.
- **Integration (real Postgres, app_role, RLS):**
  - refused before the quarter ends and with a bordereau missing;
  - once per quarter, and preparable again after a rejection;
  - maker-checker;
  - the posted journal is SYSTEM, balances, and leaves 1430, 1431 and 1420 at zero for the treaty;
  - legacy recoveries are excluded;
  - tenant isolation.
- **Contract:** the approver gate (a finance officer without FINANCE_APPROVER gets 403), and the responses validated
  against the spec.
- **Rules:** v4 parses; `source: SYSTEM` is honoured, and the PAA earning journal stays SYSTEM.
- **e2e:** prepare a statement for a treaty with a written bordereau, upload the reinsurer's statement (a real file),
  submit, have the finance approver approve, and see "Posted as".

## Gate

Affected backend classes; typecheck, lint and vitest; dev DB migrations (reinsurance V7, document V8); full e2e; merge
and push.
