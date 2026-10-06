# IFRS 17 I3a — Posting Engine Implementation Plan (DRAFT, awaiting confirmation)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Inline execution, no subagents.
> Branch `ifrs17-i3a` from main AFTER I2 merges. Nothing in this plan is compiled before then.

**Goal:** Every journal the platform posts comes from a versioned rules file, not hard-coded Java. Each line carries the
guide's movement type and the policy's IFRS 17 dimensions. PAA business posts to the PAA accounts and earns its revenue
monthly. An event the rules cannot post is queued for finance, never dropped.

**Architecture:**
- **Facts and rules are separate.** Each listener keeps one job: turning its event into one or more `PostingFacts`
  (named amounts, attributes, a source ref). These are pure functions, unit-tested without Spring.
- **The rules engine decides the lines.** It reads `finaccounting/posting-rules.yaml` and resolves the measurement
  model and dimensions from I2's `policy_classification`. It picks the one rule that matches the event, model,
  attributes and date, then builds the journal.
- **No match is never a silent drop.** No rule, or a journal the ledger refuses, goes to `unposted_event` with the
  payload kept. Finance retries it from the console once the rules are fixed.
- **PAA earning is a monthly job** over an earning schedule built from PAA invoices.

## Global constraints

- Accounts, modes and movement types are the guide's (2.4, 2.5). Rules may target AUTO and BOTH accounts only. MAN
  is refused at startup, as the database refuses it at posting.
- **User decisions (2026-10-06):**
  - Collections post direct to 1140 (no 9110 clearing until a real settlement file exists).
  - No 2410 proposal deposits.
- Behaviour is preserved: every event that posts today still posts, now through rules, the same journals except:
  - **PAA policies** go to 2142/2141 instead of 2122/2121 (I-01/I-02).
  - **Lines carry movement types and dimensions.**
  - **Lapse/NTU waivers** reverse the billed premium (A-14). The 2121/2122 pair is unchanged; the line is tagged
    `PRM_REN`.
- The rule set is versioned. Every journal records `rule_version` (column from I1).
- No hard-coded rates. Levies and commission withholding are I3b and arrive as null-rate configuration.
- Run affected classes by name. Never Prettier. Never Maven in Docker. Stop the dev backend before `clean`.

## The rules file

`backend/src/main/resources/finaccounting/posting-rules.yaml`:

```yaml
version: 1                       # bump on any change; stamped on every journal as rule_version
rules:
  - id: A-06                     # the guide's entry, or PLAT-xx for a platform-only one
    event: billing.PremiumInvoiceGenerated
    models: [GMM, VFA]           # GMM | VFA | PAA | IFRS9 | NONE (no classification: non-policy events) | ANY
    when: {}                     # attribute equality, e.g. {purpose: TOP_UP_REFUND} or {sign: NEGATIVE}
    effectiveFrom: 2020-01-01
    lines:
      - {dr: "2122", amount: amount, movement: PRM_REN}
      - {cr: "2121", amount: amount, movement: PRM_REN}
  - id: I-01
    event: billing.PremiumInvoiceGenerated
    models: [PAA]
    lines:
      - {dr: "2142", amount: amount, movement: PRM_REN}
      - {cr: "2141", amount: amount, movement: PRM_REN}
```

A line's `amount` names a fact. A line whose fact is zero is skipped, and a journal whose lines all skip is not posted
(today's "postIfAny"). `movement` is a guide code or `attr:<name>` (taken from the facts).

**Startup validation**, which refuses to start the application and names the rule:
- every account exists in the chart CSV and is AUTO or BOTH;
- every movement code is in the guide's 2.4 list;
- every `amount` is a fact name the event's extractor declares;
- no two rules overlap on event × model × `when` × date range;
- a rule has at least one Dr and one Cr line.

Balance is checked per journal at runtime (the I1 database guard); a refusal goes to the queue.

## Remap of today's postings (behaviour-preserving)

| Event | Rule(s) | Lines |
|---|---|---|
| billing.PremiumInvoiceGenerated / Increased | A-06, I-01 | Dr 2122 / Cr 2121 (PAA 2142/2141) — PRM_REN |
| billing.PremiumCollected | PLAT-A07 | Dr 1140 / Cr 2122 (PAA Cr 2142) — PRM_REN (decision 2) |
| billing.PremiumRefundDue / Reduced | PLAT-A06R | Dr 2121 / Cr 2122 (PAA 2141/2142) |
| billing.InvoiceWaived | A-14 | Dr 2121 / Cr 2122 (PAA 2141/2142) |
| claims.ClaimSettled | PLAT-B | Dr 5110 / Cr 1140 (I3b splits it into B-01..B-04 / C-04) |
| payment.EftDisbursementAwaitingExecution / Executed | PLAT-EFT | as today (5110 ↔ 2211) |
| policy.SurrenderPaid | PLAT-C06 | Dr 5110 / Cr 1140 — IC_SUR (I3b: C-05/C-06 via 2124/2213) |
| benefitpayout.PayoutPaid | PLAT-D | Dr 5110 / Cr 1140, Cr 2615 withheld (I3b: D/H splits) |
| distribution.CommissionPaid | PLAT-A05 | Dr 2123 / Cr 1140 — IACF_COM (I3b: earned A-04 + WHT) |
| policyloan.LoanDisbursed / LoanRepaid | E-01 / E-03 | Dr 2125 / Cr 1140; Dr 1140 / Cr 2125 |
| reinsurance.CessionRecorded / RecoveryConfirmed | K-01 / B-05 (interim) | as today (I3c rebuilds) |
| unitlinked.* (15 events) | F-01 .. F-09 | as the listener posts today, now with UL_* movement types and the fund dimension |

## Tasks

### Task 0 — branch, plan

Branch `ifrs17-i3a` from main after I2 merges; commit this plan.

### Task 1 — schema (finaccounting V12)

- **`unposted_event`:** tenant, id, event_type, source_ref, policy_number, payload jsonb, reason (UNMAPPED | REFUSED |
  ERROR), detail, rule_version, created_at, resolved_at, resolved_by, resolution (POSTED | DISMISSED with a reason).
  RLS, grants, unique (tenant, event_type, source_ref) while unresolved.
- **`paa_earning`:** tenant, policy_number, source_ref (the invoice), group_key, covers_from, covers_to, amount,
  earned_to_date; plus `paa_earning_run` (tenant, period, policy_number, amount), unique per policy × month, so a re-run
  earns nothing twice.

### Task 2 — facts extractors (pure)

- `PostingFacts(eventType, sourceRef, policyNumber, currency, eventDate, Map<String, BigDecimal> amounts,
  Map<String, String> attributes)`.
- One extractor per event family, moved out of today's listeners. The unit-linked extractor splits `PriceCorrected`
  into per-movement facts and `WithdrawalPriced` into two refs, exactly as today.
- Each extractor declares its fact names, which the validator reads.
- Unit tests per event, with payloads copied from the emitting modules.

### Task 3 — rules file, loader, validator

- SnakeYAML is already on the classpath.
- `PostingRuleSet` is immutable and loaded once.
- `RuleValidator` performs the startup checks above, against the chart CSV and the movement list.
- Unit tests:
  - the real file validates;
  - each invalid case is refused with the rule id named.

### Task 4 — the engine

- `PostingEngine.post(facts)`:
  1. Read the classification in force on the event date (latest row with `effective_from` ≤ date). It gives the
     model and the dimensions. A non-policy event has model NONE.
  2. Select the rule.
  3. Build the journal: source EVENT, `rule_version`, line dimensions (group key, model, movement, product,
     portfolio, channel, branch, fund attribute, and the reference type and reference).
  4. Post it through `FinaccountingApiImpl.postEntry`.
- No rule → UNMAPPED.
- A ledger refusal (any `LEDGER_*`, `PERIOD_*`) → REFUSED, with the guard's words.
- Idempotent on (event, source_ref), as now.
- Integration tests:
  - a GMM invoice and a PAA invoice go to their accounts with dimensions;
  - an unclassified policy's invoice uses model NONE → UNMAPPED (queued, not posted);
  - a redelivery posts nothing.

### Task 5 — listeners on the engine

- Every existing listener becomes extractor → engine. `PostingRule` and `GlPostingCalculator` are deleted.
- Every existing posting test keeps its meaning, with the new accounts where the remap table changes them.
- Run all finaccounting, billing-posting, unit-linked accounting, payout, loan and funeral posting classes.

### Task 6 — PAA earning (I-03)

- **What billing says:** billing adds `coversFrom` and `coversTo` to `PremiumInvoiceGenerated`.
  - An instalment covers its due date to the day before the next due date.
  - A credit-life enrolment-file premium covers what the user decides (**open question Q1**).
- **What finaccounting records:** a PAA invoice writes a `paa_earning` schedule row.
- **The job:** a monthly job, on the last day of the month in EAT (`CIVIL` zone, never UTC), earns each row's cover
  elapsed in the month. It posts Dr 2141 / Cr 4160 per policy × month, source SYSTEM, keyed so a re-run earns nothing.
- **Reversals:** a waiver or refund on a PAA invoice shortens what is left to earn.
- **Tests:**
  - a 12-month invoice earns 1/12 per month;
  - a re-run earns nothing;
  - a waived invoice stops earning.

### Task 7 — queue, rules page, API

- `GET /finance/unposted-events` (open first).
- `POST /finance/unposted-events/{id}/retry` re-runs the engine with the current rules.
- `POST .../dismiss` takes a reason.
- `GET /finance/posting-rules` returns the loaded rule set, read-only.
- OpenAPI for all four.
- Spec §7.6: a non-empty queue blocks the period lock. `AccountingPeriods.lock` refuses with "N events are not posted;
  post or dismiss them before the period locks".

### Task 8 — console

- **Finance → Posting rules:** a read-only table by event, model, entry id and lines.
- **Finance → Unposted events:** the queue, with payload, reason, retry, and dismiss with a reason.
- **Journal detail:** shows movement types (the dimensions panel exists from I1).
- **Unit tests:** the dismiss form.
- **e2e:** the rules page lists A-06 and I-01, and the queue page renders. No test-only endpoint is added to seed an
  unposted event: retry and dismiss are proven in backend integration and contract tests.

### Task 9 — dev DB, gate, merge

- Apply V12.
- No backfill: the ledger is journals already posted. New lines get dimensions from now on.
- Restart the backend.
- **Gate:** affected classes, frontend, full e2e.
- Self-review, then merge `--no-ff` and push.

## User answers (2026-10-06)

- **Q1 — (a).** A credit-life single premium is earned straight-line over each member's loan term. Billing states
  coversFrom/coversTo per member, so a file is many earning-schedule rows.
- **Q2 — yes.** The rules file is the source of truth; the console page is read-only until the database editor with
  maker-checker.

## Questions as asked

- **Q1 — How a credit-life single premium is earned under PAA.** One enrolment-file premium covers many borrowers,
  each over their own loan term, often longer than 12 months. Earn it:
  - (a) straight-line over each member's loan term (billing states it per member, so a file becomes many schedule
    rows);
  - (b) straight-line over the scheme's policy term;
  - (c) over 12 months.

  (a) is the accurate one and the most work; the actuary may prefer an expected-claims pattern later (a
  register election).
- **Q2 — The rules page.** Read-only now (decision D3); the database editor with maker-checker comes later. Confirm the
  file is the source of truth until then.
