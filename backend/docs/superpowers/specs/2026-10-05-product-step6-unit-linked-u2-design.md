# Product step 6 / U2 — unit-linked: switches, withdrawals, top-ups, surrender charges, redirection, statements

**Date:** 2026-10-05
**Builds on:** U1 (`2026-10-05-product-step6-unit-linked-u1-design.md`) — the fund register, two-person prices,
forward pricing with a daily cut-off per fund, the append-only unit ledger, pending orders, exits and the
`fund_liability` true-up (U1 deviation D1).
**Module:** `unitlinked` (U1's), with additions to `product`, `policy`, `payment`, `finaccounting`, `communication`.

## Decisions (the user's answers, 2026-10-05)

| # | Decision |
|---|---|
| Q1 | A **switch** prices both legs on the **same valuation date**. |
| Q2 | **Switch charge:** N free switches per policy year, then a flat fee per switch — both set per version. |
| Q3 | A **withdrawal** is sold from **named funds, or in proportion to holdings** when none are named. |
| Q4 | Whether a withdrawal **reduces the sum assured** is **configurable per version, default no**. |
| Q5 | **Top-ups** have their **own allocation percent** and minimum, set per version. |
| Q6 | The **surrender charge** applies to **full surrenders and partial withdrawals**, and is **never** charged when the policy lapses because its fund ran out. |
| Q7 | A **non-payment lapse** with value **carries the surrender charge**. |
| Q8 | **Premium redirection** is recorded by **one** staff member, audited, applying to premiums received after it. A **switch** is also one person, audited; withdrawals (money paid out) take two. |
| Q9 | **Statements:** the **calendar-year** statement for every policy **plus** an **on-demand** statement for any period. On-demand adds to the annual one; it does not replace it. |
| A1 | One **table per request type** (switch, withdrawal, top-up), and redirection as a **split history**. |
| A2 | A policy's terms are fixed by its **pinned product version**, made **immutable in the database** by triggers once published. No per-policy copy. |

## 1. Version terms (product V26)

New columns on `product.unit_linked_terms`, set per version and checked at publish by `UnitLinkedPlanValidator`:

| Term | Meaning | Rule |
|---|---|---|
| `free_switches_per_year` | Switches per policy year with no fee | integer ≥ 0 |
| `switch_fee` | Flat fee per switch beyond the free ones | ≥ 0 |
| `minimum_withdrawal` | Smallest withdrawal accepted | > 0 |
| `minimum_remaining_value` | Value that must stay after a withdrawal (at the latest prices) | ≥ 0 |
| `withdrawal_reduces_sum_assured` | Whether a withdrawal cuts cover by the gross amount | boolean, default false |
| `top_up_allocation_percent` | Share of a top-up that buys units | > 0 and ≤ 100 |
| `minimum_top_up` | Smallest top-up accepted | > 0 |

New table `product.unit_linked_surrender_charge` (`terms_id`, `from_year`, `to_year` nullable = onwards,
`percent`): starts at year 1, contiguous, the last band open-ended, 0–100. A version may set 0% throughout.

**A feature is offered only when its terms are present.** V26 gives versions published under U1 the
conservative defaults — switching off (`free_switches_per_year` null), withdrawals off (`minimum_withdrawal`
null), top-ups off (`top_up_allocation_percent` null), no surrender charge (no bands) — so no existing policy
gains a capability nobody configured, and no charge it was not sold with.

**Immutability (A2).** After V26's backfill, triggers on `unit_linked_terms`, `unit_linked_surrender_charge`
and every U1 unit-linked terms table (funds offered, allocation bands, mortality, premium minimums) refuse
UPDATE and DELETE on rows of a published version — the same append-only rule as U1's prices and ledger. Every
read resolves terms from the **policy's** `product_version_id` (already true of all U1 paths), so a later
version can never change an existing policy.

## 2. Fund switches

- **Request** (one staff member, audited) — `unitlinked.switch_request`: per source fund, the percentage of its
  units to move (1–100; 100 = all); the target split in whole percents totalling 100 across funds the policy's
  version offers that are OPEN. Refused if the policy is not in force or is frozen, another switch is WAITING,
  a source fund holds no units, or the version does not offer switching.
- **Binding:** each involved fund's bound date from the request instant and that fund's cut-off
  (U1 `BindingRule`); the switch binds to the **latest** of them.
- **Execution** — on the **first valuation date ≥ the bound date for which every involved fund has an
  APPROVED price**, inside the price approval's transaction (U1's pricing run):
  1. `SWITCH_OUT` entries — the units moved out of each source fund at that date's price;
  2. `SWITCH_FEE` money entry when the switch is beyond the version's free switches in the current policy year
     (counted by EXECUTED switches in that policy year), taken from the proceeds;
  3. `SWITCH_IN` entries — the net proceeds bought into the target funds by the target split, same date's prices.
- **Statuses:** WAITING → EXECUTED | CANCELLED. A switch WAITING when the policy freezes for an exit is
  CANCELLED (the exit sells everything).
- **Ledger:** value stays in 2150; each fund's `fund_liability` moves by the amount switched out and in; the fee
  posts DR 2150 / CR 4310.
- **Corrections:** a corrected price on the switch date redoes both legs as U1 corrections redo any movement;
  the fee stands.

## 3. Partial withdrawals and the surrender charge

- **Request** (one staff member) — `unitlinked.withdrawal_request`: a **gross** amount; optionally named funds
  with an amount each (summing to the gross), otherwise proportional to each fund's value at its latest
  approved price; a payee.
- **Checks** (at request and again at approval): in force, not frozen; `minimum_surrender_years` passed; no
  other WAITING withdrawal, surrender or switch; amount ≥ `minimum_withdrawal`; value remaining at the latest
  prices ≥ `minimum_remaining_value`; a named fund's amount ≤ its value; when
  `withdrawal_reduces_sum_assured`, the reduced sum assured ≥ the version's minimum multiple of the annual
  premium. The figures shown are indications at the latest prices.
- **Approval** by a second person (finance or admin). The sale binds at the **approval** instant.
- **Execution** at the first approved price ≥ the bound date: `WITHDRAWAL_SALE` per fund, units = amount ÷ price,
  capped at the fund's holding (a price fall sells the whole fund and the shortfall is recorded on the request);
  `SURRENDER_CHARGE` money entry at the percent for the policy year of the valuation date; payout of proceeds
  less charge through payment as `WITHDRAWAL_PAYOUT`, idempotency key `unit-linked:withdrawal:<id>`; when
  configured, the sum assured is reduced by the gross amount through a policy endorsement.
- **Statuses:** REQUESTED → APPROVED → PRICED → PAID | CANCELLED (a policy frozen for an exit before pricing).
- **The surrender charge elsewhere:**

  | Exit | Charge |
  |---|---|
  | Full surrender | yes — on the value sold, at that policy year's percent |
  | Partial withdrawal | yes |
  | Lapse for non-payment, with value | yes |
  | Lapse because the fund ran out | never |
  | Death, maturity, free-look | never |

- **Ledger** (5100 nets to zero): sale DR 2150 / CR 5100; charge DR 5100 / CR 4310; payout DR 5100 / CR cash on
  `unitlinked.PayoutPaid`.

## 4. Top-ups and premium redirection

**Top-ups** — `unitlinked.top_up`:
- **Request** (one staff member): amount ≥ `minimum_top_up`, a payer reference, optionally its own split
  (else the split in force). Policy in force, not frozen, version offers top-ups. The server **reads the
  Idempotency-Key**: a retried request returns the same top-up and collects once.
- **Collection** through payment under a new purpose `UL_TOP_UP` (payment migration); every collection
  consumer filters by purpose, so billing never treats it as a premium.
- **On confirmation:** `top_up_allocation_percent` buys units, the rest is an `ALLOCATION_CHARGE`; BUY orders
  bind by the confirmation instant and each fund's cut-off. The sum assured does not change.
- **Policy frozen or ended when the money arrives:** an open exit takes it as returned money; a finished exit
  refunds it in full through payment.
- **Ledger:** confirmation DR cash / CR 2140; allocation as U1 (DR 2140 / CR 2150, CR 4310 the charge).

**Premium redirection** — `unitlinked.premium_split`, a history replacing U1's single current split (migrated as
each policy's first row): each row is a split, the instant it takes effect, and who recorded it. Every premium,
regular or top-up, is allocated by the split in force at the instant it was **received**; a premium already
waiting keeps its split. Whole percents totalling 100; offered, OPEN funds; in force, not frozen; one staff
member, audited.

## 5. Statements

- A builder, a PDF renderer and filing through the document module, in `unitlinked`, following the savings
  statement pattern.
- **Content for a period:** opening and closing units per fund, each valued at the fund's latest approved price
  on or before that date with the price date shown — "no price yet" where none exists, never zero and never a
  passed-off older price; every movement in the period with units, price and date; totals paid in, charges by
  type, paid out, change in value; the prices used.
- **Annual:** a daily drain files last calendar year's statement in early January for every unit-linked policy
  that held units at any time in the year, once per policy and year (`unitlinked.unit_statement`, kind ANNUAL),
  with an SMS summary (template `UL_STATEMENT`).
- **On demand:** staff pick a period (end ≤ today); built and filed at once (kind ON_DEMAND); no SMS. The policy
  page lists every statement.

## 6. API and console

**API** (`openapi-unitlinked.yaml`; decimals as strings): `POST|GET /policies/{n}/switches`;
`POST /policies/{n}/withdrawals`, `POST /withdrawals/{id}/approval`, `GET /policies/{n}/withdrawals`;
`POST|GET /policies/{n}/top-ups`; `PUT|GET /policies/{n}/premium-split`; `POST|GET /policies/{n}/statements`.
The product spec gains the V26 terms; the event catalogue gains the switch, withdrawal, top-up and statement
events.

**Console:** the new terms in the unit-linked section of the publish form (surrender charges as a pasted grid);
on the Units tab, Switch, Withdraw (gross, charge, net, value left, new sum assured — labelled indications; a
second person approves), Top up and Change future split, plus the statements list and the on-demand form.
WAITING switches and withdrawals appear in "Waiting for a price".

## 7. Testing

Backend integration: a switch prices both legs on one date and waits through one fund's holiday; the free
allowance then the fee; a withdrawal proportional and by named funds, capped by a price fall; the surrender
charge on surrender, withdrawal and non-payment lapse but not exhaustion or death; a cover reduction refused
below the floor; a top-up allocated at its own rate, credited once on a retried request, refunded on a frozen
policy; a redirection applying only to later premiums; an annual statement filed once and an on-demand one;
the published-terms triggers refusing an edit; 2150 = units × price after every step. Frontend unit tests for
the new forms and gates. One e2e: switch, withdraw (charge, second-person approval), top up, redirect, an
on-demand statement.

## Out of scope

Automatic regular switching (lifestyling), customer self-service in a portal, changes to the death benefit.
