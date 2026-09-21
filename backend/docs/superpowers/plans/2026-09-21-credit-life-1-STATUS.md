# Credit Life, Plan 1 — status

**Branch:** `credit-life-1-the-contract` (10 commits, not merged)
**Date:** 2026-09-21
**Spec:** `../specs/2026-09-21-credit-life-design.md`
**Plan:** `2026-09-21-credit-life-1-the-contract.md`

---

## What works now

A credit-life product can be authored and published. A scheme can be issued against a
lender. Borrowers enrol from their loans, cover backdated to disbursement. A resubmitted
loan is refused by its account number. A borrower over the free cover limit is capped
**and** referred for evidence. A claim resolves to the borrower who died rather than to
the whole book.

| Task | | Commit |
|---|---|---|
| 1 | `CREDIT_LIFE` product category | `4a39acc` |
| — | `createProduct` stops mislabelling integrity violations | `e58043c` |
| 2 | `AmortisationCalculator` — outstanding principal on any date | `f6e81ab` |
| 3 | Freeform members (`policy/V13`) | `b5ff21b` |
| 4 | `AMORTISING_LOAN` basis + loan columns (`policy/V14`) | `7f45135` |
| 5 | A credit-life product can be issued as a scheme | `63d57c7` |
| 6 | Enrol a borrower against their loan | `46c065d` |
| — | Credit-life claims; two more mislabelling catches | `a53ff88` |
| 8 | `referForEvidence` gets its first caller | `a69b667` |
| **7** | **`claimableCover` amortises** | **⛔ BLOCKED** |

---

## The one blocker

**Task 7 needs one answer from the client: how does cover decline?**

Straight-line to zero over the term, a reducing-balance schedule, or not at all?

Neither real client file carries an outstanding balance, and both charge premium on the
disbursed amount, so their present practice may not decline at all. Writing this now
would encode a guess into the single number a death claim is valued against.

**Everything underneath it is already built.** `AmortisationCalculator` implements both
methods and is tested against LOLC's real shape. `FLAT_RATE` computes
`principal × (n−k)/n` — straight-line — and reads no interest rate, which matters because
neither lender states one. Whichever way the answer goes, Task 7 is a small change at one
seam in `claimableCover`.

---

## What the real client files changed

Two schedules arrived mid-build (`sample data/`, gitignored — they carry ~345 real
borrowers' names, dates of birth and loan amounts). They refuted three planned decisions:

- **No instalment amount, no interest rate.** The method-inference mechanism could not
  run. Removed; the interest method is stated once per scheme instead.
- **No loan account number.** Both lenders have been asked to add one. Our template
  requires it and a row without it is refused.
- **No outstanding balance anywhere.** Became the Task 7 blocker above.

They also settled one thing outright: premium is a percent **per annum on the disbursed
amount** — LOLC 0.5%, BUMACO 0.6%. Two lenders, two rates, confirming it belongs on the
scheme.

---

## Defects found and fixed that were not in the plan

1. **A credit-life claim was unanswerable, and the fallback was worse.** `claimableCover`
   and `dischargeForSettledClaim` keyed on `GROUP_LIFE` alone. Naming the borrower was
   refused; naming nobody valued the claim at the scheme total — the whole book. The
   discharge side would have surrendered every other borrower's cover.
2. **`Policy.restateSumAssured` refused `CREDIT_LIFE`,** so a lender's contract would have
   gone on stating its issue-day total while every monthly file added cover.
3. **The opening schedule never checked commencement.** A loan disbursed before the scheme
   existed would have been enrolled as risk nobody priced.
4. **The opening schedule rejected every freeform row,** so a scheme could gain a freeform
   member only after issuance — useless for a product that enrols its first batch at
   issuance.
5. **`GroupMemberAdded` published through `Map.of`,** which throws on a null value: the
   first freeform member would have failed at the event, after validation passed.
6. **`listMembers` short-circuited on no party match,** making a credit-life roll — where
   every member is freeform — completely unsearchable.
7. **Three catches reporting every integrity violation as a duplicate**
   (`createProduct`, `registerCorporate`, `createAccount`). The first cost real diagnosis
   time on task 1: a category CHECK violation was announced as a duplicate product code
   for a code that had just been invented.

---

## Deliberate decisions worth not relitigating

- **`MemberInput` and `IssueGroupSchemeRequest` keep convenience constructors.** Adding
  freeform members and loans changed neither of the 37 existing call sites, because every
  one of them already meant `PARTY` with no loan.
- **Two freeform members may share a name.** A scheme covers a father and a son; refusing
  the second would leave a real life uninsured to enforce a uniqueness the data cannot
  support. Credit life keys on the loan account number instead.
- **Only above-FCL borrowers are promoted to parties.** Freeform exists to keep the KYC
  queue clear of people nobody needs to identify; somebody over the limit is precisely
  somebody you do.
- **A `PARTY` member may carry a loan.** A rule forbidding it was added in task 6 and
  removed in task 8: it blocked a lender naming an existing client, and it is the shape
  promotion produces.
- **`interestMethod` has no setter.** A lender who changes how their book repays is a new
  scheme; every member already enrolled was valued on the old answer.

---

## Still open, outside this plan

- **`plans/2026-09-10-group-scheme-substitution-and-notices.md` must be amended before it
  lands.** Its §4 removes `addMember` outright. Credit life adds borrowers every month.
  Agreed fix: fixed headcount becomes a per-scheme property. That plan is uncommitted, so
  this is nearly free now and a migration later.
- **Nine client questions** (`../specs/2026-09-21-credit-life-client-questions.md`),
  including §6 of the requirements table, now five requests overdue.
- **Plans 2–7**: bulk CSV intake; premium and exits; the claim and payout path;
  `regreporting` and notices; the console; the bank portal.
- **The two `2026-09-10-group-*.md` plan docs are still untracked** in the working tree.
  This spec cites both by path, so those references dangle until somebody commits them.
