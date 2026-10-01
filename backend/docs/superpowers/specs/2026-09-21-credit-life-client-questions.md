# Credit life — what we still need from you

**Date:** 2026-09-21
**Subject:** §4 of the underwriting requirements table (credit life)

We have completed the design for credit life cover. It is ready to build, and we are not
waiting on anything technical.

We are waiting on nine answers from you. Seven are short. One is a document you have been
asked for five times. One is a spreadsheet, and it is the most useful thing you can send
us — it answers four of the others by itself.

Where we have had to assume something in order to keep designing, we say so and we say what
we assumed. If an assumption is wrong, tell us now; each one is cheap to change today and
expensive to change after we build it.

---

## Start here: we need one new column, and that is all

We have now seen two of your real schedules — LOLC's June file and the BUMACO August
template. They already carry almost everything we need.

**Attached: `credit-life-enrolment-sample.csv`**, the format we will ask lenders to send.
It is nine columns, and your existing sheets already have eight of them: the client's
name, gender, date of birth, the disbursed amount, the term and the disbursement date.

**The one thing missing is a loan account number.** Your core banking system holds one for
every loan; your schedule does not currently include it. We need it because it is how we
tell one loan from another — how a resubmitted file is recognised as the same loans rather
than a second set, and how a claim is matched to the right borrowing. Two loans to the
same person are two separate covers, and a name cannot distinguish them.

We originally expected to ask for four new columns. Having seen your files, we removed
three of them from the format instead:

- **Interest rate** — not needed for the way we intend to value cover.
- **Repayment frequency** — we will agree this once per lender, not per loan.
- **First repayment date** — we will calculate it.

Please send **ten real loans** in this format, names removed if you prefer. It takes
minutes and it confirms in one go that the account number is available and that the rest
of the columns match what you hold.

---

## 1. The premium rate

We understand the bank pays an agreed percentage. We need it written down, with a worked
example, and we need one thing made explicit: **is the percentage per year, or once for the
whole loan?**

Take a 10,000,000 TZS loan at 0.5%. If the rate is per year, a five-year loan costs five
times what a one-year loan costs. If it is a single flat charge, both cost the same — which
would mean insuring five years of risk for the price of one.

**We have assumed per year, on the original loan amount.** Please confirm.

### ANSWERED 2026-09-29 — and it is neither, because it is not one answer

The question had a hidden premise: that both lenders price the same way. They do not, and the
answer was read off their own schedules in `sample data/` rather than described.

**Bumaco charges the percentage ONCE, flat, and the term does not enter the price.** Their May
file carries six loans of 2, 4, 6, 6, 12 and 12 months and every one is exactly 0.6% of the
disbursed amount — the two-month loan costs what a twelve-month loan of its size costs. The
file's own total, 111,000 on 18,500,000, agrees.

**LOLC charges it once per policy year, each year on the balance still outstanding.** That is
what the 1st-to-5th-year columns on their sheet are. A 10,400,000 loan over 18 months pays
0.5% of the full amount and then 0.5% of a third of it; a 10,500,000 over 23 months pays
52,500 then 25,108.6956…, which their sheet carries to ten decimals.

Both are charged ONCE, at enrolment. LOLC's per-year figures are how their total is arrived
at, not an instruction to invoice annually, so one-file-one-invoice holds for both.

The assumption in the table below — per year on the original amount — matched neither. It
undercharged Bumaco by 49% on a real file and overcharged LOLC on any term over a year. It
survived because all three bases agree exactly on a twelve-month loan, and every fixture used
one. It is now `CreditLifePremiumBasis`, stated per scheme, with no default.

## 2. Commission clawback

The bank earns commission on each loan insured.

If a borrower repays early, we refund the unused part of the premium. **We have assumed the
bank's commission is refunded in the same proportion.** Without that, we return money to the
borrower while the bank keeps commission on money that came back — a loss to the insurer on
every early settlement.

Please confirm the bank will accept this, because it belongs in the scheme agreement.

## 3. The free cover limit

Below a certain loan size we insure the borrower with no medical evidence at all. Above it we
cannot, because a spreadsheet cannot carry medical evidence.

**What is that limit?**

**And please confirm what happens above it.** We have assumed the borrower is covered **up
to** the limit, with the excess uncovered until medical evidence is provided — so a
30,000,000 TZS loan against a 25,000,000 limit gives 25,000,000 of real cover from day one,
and the bank carries the remaining 5,000,000 as ordinary credit risk.

The alternatives are no cover at all until accepted, or full cover regardless. This was
flagged as an open assumption in the group business design and has never been confirmed.

## 4. Exclusions

Because there is no underwriting below the free cover limit, exclusions are the only
protection against someone taking a large loan immediately after a terminal diagnosis.

**We have assumed:**

- Death by suicide is not covered in the first 12 months
- Death from a condition the borrower already had at enrolment is not covered in the first
  12 months
- There is **no** general waiting period — death from any other cause is covered from day one

Please confirm, or give us the wording your policy document will carry.

## 5. How we learn a loan has ended

Monthly files tell us about new loans. Nothing in them tells us a loan was repaid early,
refinanced or written off — and until we know, we cannot refund premium and the borrower
stays on our books as insured.

**We have assumed the bank sends a separate short file listing loans that have ended, plus a
full list of live loans once a quarter so we can catch anything missed.**

How often can the bank produce the first one? And is the quarterly reconciliation
acceptable?

## 6. Telling the borrower when they are not covered

When a file arrives, some rows will fail — a missing date of birth, a borrower too old for
the product, a duplicate. Those borrowers are **not insured**, and our report says so
explicitly against each one, and the bank must acknowledge it.

**Does the borrower get told as well?** We recommend yes. If a row fails and nobody outside
the bank ever knows, the first time it surfaces is at a death claim.

## 7. The TIRA product class

A product cannot be published on our platform without a TIRA filing reference. **Which
product class does credit life file under?**

## 8. Whether your loans have payment holidays

Some loans have a moratorium before the first repayment, an interest-only period, or a
balloon payment at the end. These do not repay in a straight line, so we treat them
differently.

**Does the book contain any of these?** If you are not sure, the ten real loans above will
show us.

This matters more than it did a week ago. We removed the "first repayment date" column
from the format, which means we now assume every loan starts repaying one period after it
is paid out. That is right for an ordinary loan and wrong for one with a three-month
holiday. **If your book contains payment holidays, tell us and the column comes back** —
it is a small change now and an awkward one later.

## 9. §6 of the requirements table

Still outstanding since 3 September, and requested five times. We do not know what it
contains, so we cannot say what it changes. If it is not coming, please tell us that instead
and we will proceed on what we have.

---

## The one answer that is holding everything up

**Does the amount insured go down as the loan is repaid — and if so, how?**

This is the single question we cannot proceed past, and we want to be plain about why we
are asking it rather than assuming.

We were told the cover pays whatever is still owed. But neither of your real schedules
carries an outstanding balance at all, and both charge the premium on the **full disbursed
amount** for the whole term. Those two things do not sit together: a policy whose cover
falls month by month is not usually priced on the amount at the start.

So, one of three:

1. **Cover falls in a straight line** from the disbursed amount to zero across the term.
   This is a standard credit-life design and it is what we have built for.
2. **Cover follows the actual loan balance**, which falls more slowly at first because
   early instalments are mostly interest. This needs the interest rate on every loan,
   which would add a column back to the file.
3. **Cover stays at the disbursed amount** for the whole term, which is what your current
   premium basis implies and what your spreadsheets actually describe today.

They are materially different. On a 10,400,000 loan halfway through an 18-month term,
option 1 pays 5,200,000 and option 3 pays 10,400,000 — double.

A worked example of one real claim you have paid would answer this faster than a
description.

---

## What we have assumed, in one place

If any of these is wrong, it is much cheaper to say so now.

| | We assumed |
|---|---|
| Premium | ~~An agreed percent **per year** of the original loan amount, charged once~~ — **ANSWERED 2026-09-29, and the assumption was wrong for both lenders.** See below. |
| Payout | The **outstanding** balance, capped at what the bank confirms is actually owed |
| If in arrears | Cover continues and follows the original repayment schedule |
| Cover starts | On the loan's **disbursement date**, backdated |
| Perils | **Death only.** No disability, no retrenchment |
| Who is paid | **The bank**, directly, to clear the debt |
| If cover is less than the debt | We pay our part; the remainder is the bank's credit risk |
| Above the free cover limit | Covered up to the limit; excess needs medical evidence |
| Too old, or loan too long | The row is **rejected** and the borrower is **not covered** |
| Restructured loans | Treated as a new loan: old cover ends, new cover starts |
| Early repayment | Premium refunded in proportion |
| Who may submit a file | Bank staff or insurer staff — and a **second** insurer staff member must approve it before anyone is covered |
