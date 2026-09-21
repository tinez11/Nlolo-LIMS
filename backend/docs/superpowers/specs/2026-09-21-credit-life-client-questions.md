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

## Start here: ten real loans

**Attached: `credit-life-enrolment-sample.csv`.**

This is the form in which banks will send us their borrowers. Please fill in **ten real
loans** from an actual book — names removed if you prefer, but everything else real.

It takes minutes and it settles four separate questions we would otherwise have to ask you
one at a time:

- Whether a loan account number exists on your schedule, and what it looks like
- Whether you record a term in months or a maturity date
- Whether you record a date of birth or an age
- Whether the interest rate you quote is the flat rate or the effective rate

If any column is one you do not hold, leave it blank and say so — that is an answer too.

---

## 1. The premium rate

We understand the bank pays an agreed percentage. We need it written down, with a worked
example, and we need one thing made explicit: **is the percentage per year, or once for the
whole loan?**

Take a 10,000,000 TZS loan at 0.5%. If the rate is per year, a five-year loan costs five
times what a one-year loan costs. If it is a single flat charge, both cost the same — which
would mean insuring five years of risk for the price of one.

**We have assumed per year, on the original loan amount.** Please confirm.

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

## 9. §6 of the requirements table

Still outstanding since 3 September, and requested five times. We do not know what it
contains, so we cannot say what it changes. If it is not coming, please tell us that instead
and we will proceed on what we have.

---

## One question we have withdrawn

We previously asked whether your loans use **reducing-balance or flat-rate interest**. This
was the single question blocking the whole product.

**You no longer need to answer it.** We have added one column to the file — the monthly
instalment, which every loan already has — and the system works out the method for itself by
checking which one produces that instalment. The two differ by 16% to 30%, so there is no
ambiguity.

It also gives us a free accuracy check: if the instalment matches neither method, something
in that row is wrong, and we reject it with a reason instead of quietly insuring the wrong
amount.

---

## What we have assumed, in one place

If any of these is wrong, it is much cheaper to say so now.

| | We assumed |
|---|---|
| Premium | An agreed percent **per year** of the original loan amount, charged once |
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
