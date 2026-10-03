# Product step 5 — immediate annuities (sub-project D1)

**Status:** design agreed 2026-10-03. Next: implementation plan.
**Builds on:** step 0 (rolling billing, pinned version), step 2 (payout engine, `benefitpayout`:
streams, proof of life, two-person approval, payment runs, free-look), step 3 (accumulation),
step 4 (bonuses, merged after its gate).

## 1. Where this sits

The guide's annuity sections ask for an income for life bought with a single premium, with
guaranteed-period, joint-life, escalating and capital-protected variants, and for deferred
annuities and pensions that vest from savings. Today `ANNUITY` is a category label that every
value basis refuses on purpose (step 3 Q9: "a pension must not be sellable before it can vest").
The payout engine pays `INCOME` rows only up to a maturity date; nothing turns a purchase price
into an income, and nothing pays until death.

Sub-project D is split in three. This spec is D1.

| Part | Subject | State |
|---|---|---|
| **D1** | **Immediate annuity: a single premium buys an income for life, priced from the version's rate table** | **this spec** |
| D2 | Deferred annuity / pension vesting: an account policy's balance (less any commuted lump sum) buys a D1 annuity at its vesting date | after D1 |
| D3 | Group pension: member accounts in a scheme, each vesting into its own D1 annuity | after D2 |

## 2. Decisions

All answered by the user on 2026-10-03.

| # | Question | Decision |
|---|---|---|
| Q1 | How the income is worked out | **A rate table on the version**: annual income per 1,000 of purchase price, by age at purchase, per annuity form. Sample tables until the actuary supplies real ones; publishing needs an actuarial basis reference and date, as step 1's cash-value table does. A mortality-and-interest engine may later *generate* such tables; it is not part of D1. |
| Q2 | Annuity options | **All six, as configurable combinations, never hard-coded products:** life only; life + 5-year guarantee; life + 10-year guarantee; joint life / last survivor; escalating (fixed annual %); capital protected. A version publishes a list of **forms**, each one combination of four independent settings (§3.2). |
| Q3 | Joint-life pricing | **The annuitant's age plus an age-difference band** for the joint life (e.g. 0–5 years younger, 5–10, 10+, or older), not a full two-age grid. |
| Q4 | Sex | **The version chooses per form**: `UNISEX` or `BY_SEX`. On a `BY_SEX` form, a purchase for a life whose sex is not recorded is refused in words that say why, as premium pricing refuses. On a joint `BY_SEX` form both sexes must be recorded. |
| Q5 | Frequency | **One annual grid per form plus a factor per offered frequency** (monthly, quarterly, semi-annual, annual; annual fixed at 1.0000). **Timing is stated by the version — `ARREARS` or `ADVANCE` — with no default.** |
| Q6 | Purchase | **The normal underwriting case, with an annuity light path**: no medical assessment required; the decider records that age evidence was confirmed; ACCEPT auto-issues; the policy is PROPOSED until the single premium is collected. **The income locks on the collection date**, at the rate table in force that day. |
| Q7 | Death | **Through the existing death claim.** A guaranteed remainder is paid as **continued instalments to the beneficiaries** until the guarantee ends. The capital-protection refund is netted (§5.2). On a joint form the first death switches to the survivor percentage; only the last death triggers the guarantee and refund rules. |
| Q8 | Tax | **The withholding mechanism is built; the rules are configurable data** (rate, payout kinds, effective dates, legal reference), platform-level, set by finance — never a rate in code and never a per-version field. |
| Q9 | Cancellation | **Free-look applies; no surrender, ever.** An ANNUITY version cannot carry a cash-value table, surrender charges, a loan value or paid-up; each is refused by name. |
| Q10 | General ledger | **Existing postings plus a withholding line; no reserve.** A paid instalment posts DR benefit expense (gross) / CR cash (net) / CR withholding tax payable (withheld). Annuity reserves wait for the IFRS 17 model, the same known gap as steps 3 and 4. |

Two consequences agreed with the design:
- A quote is re-run at underwriting acceptance, so a purchase that cannot be priced is refused
  **before** any money is taken, not after.
- Overdue proof of life suspends payment; it is never treated as a death. Only an approved death
  claim ends an annuity.

## 3. What the product publishes

### 3.1 Tables — product V22

V21 is step 4's. Every table is keyed by `product_version_id`; a row exists only for an ANNUITY
version, so no other version and no existing test class reads them (step 3's lesson: a mapped
column on `product_version` would break every class that issues a policy). RLS with the
`NULLIF` predicate, as every policy since 2026-09-10.

| Table | Columns (beyond ids and tenant) |
|---|---|
| `version_annuity_terms` | `timing` (`ARREARS` / `ADVANCE`, no default), `proof_of_life_interval_months`, `joint_age_difference_min`, `joint_age_difference_max` (the range of differences the joint bands must cover; null when no joint form), `basis_reference`, `basis_date` |
| `annuity_form` | `form_code` (unique per version, e.g. `LIFE-10G-3E`), `guarantee_years`, `joint`, `survivor_percent`, `escalation_percent`, `capital_protected`, `rate_basis` (`UNISEX` / `BY_SEX`) |
| `annuity_rate_row` | `form_id`, `sex` (null on `UNISEX`), `age`, `age_difference_from`, `age_difference_to` (joint forms only; difference = annuitant age − joint-life age, negative when the joint life is older), `annual_rate_per_mille` |
| `annuity_frequency` | `frequency`, `factor` |

### 3.2 A form is four settings

| Setting | Values |
|---|---|
| Guaranteed period | 0–30 whole years (0 = none) |
| Joint life | off; or on, with a survivor percentage 1–100 |
| Escalation | 0–10% a year, fixed, applied on each anniversary of the first payment |
| Capital protected | yes / no |

"Life only" is (0, off, 0%, no). "Joint life, 10-year guarantee, 3% escalating" is another row.
The engine reads only the four settings; no form is special-cased. A purchase names a form by
its code, and the server refuses a code the version does not list.

### 3.3 What an annuity version is

- Category `ANNUITY`. Premium frequency `SINGLE` only. The purchase price is the premium, and the
  version's sum-assured bounds act as purchase-price bounds.
- No authored payout schedule: the annuity module opens the stream (§4.4).
- Free-look days are required, as on every individual product (step 2).

### 3.4 Validation — `AnnuityPlanValidator`

Pure and static, like `BonusPlanValidator`; the console mirrors it message for message, and a
schema test asserts every message lands on a rendered field (step 4 L10).

- Annuity terms only on `ANNUITY`, and an `ANNUITY` version must have them.
- At least one form and one frequency; `timing` stated; an actuarial basis reference and date.
- Refused by name on an `ANNUITY` version: a cash-value table, surrender charges, an account or
  deposit plan, bonus terms, a payout schedule, any premium frequency but `SINGLE` (Q9).
- Per form: guarantee 0–30; survivor % 1–100 only when joint and absent otherwise; escalation
  0–10; no two forms with the same four settings and rate basis.
- **Coverage, no gaps** (refuse rather than default, as premium pricing does): every age within
  the version's entry-age bounds has a rate in every form; a `BY_SEX` form has both sexes for
  every age; a joint form's bands are contiguous, do not overlap, and cover
  `joint_age_difference_min`..`max` at every age.
- Rates > 0. Frequency factors 0 < f ≤ 1; annual, if offered, is exactly 1.0000.

Publishing gets a 10th `publishVersion` overload ending `..., BonusPlan bonusPlan, AnnuityPlan
annuityPlan, String publishedBy`; the bonus overload becomes its delegate and the overload-count
guard in `ProductApiIntegrationTest` goes 9 → 10. `ProductApi.resolveAnnuityPlan(versionId)`
answers `AnnuityPlan.none()` for every other version — the cheap gate every caller asks first.

## 4. Buying it

### 4.1 The quote

`POST /annuity-quotes` in the new module: product, form code, frequency, purchase price, the
annuitant (party id), and on a joint form the joint life (party id). Returns the annual income,
the instalment, the rate cell (form, sex, age, band, rate) and the first-payment rule. A read:
nothing is stored. Refusals are 422 in the validator's and the pricer's words ("This form is
priced by sex and Amina Owner's sex is not recorded", "No rate for age 91 on form LIFE-0G").

### 4.2 The case

For an ANNUITY product the underwriting case records the **annuity choice** — form code,
frequency, joint life party — in its own table (underwriting V-next), keyed by case, so no other
case reads it. The case's sum assured is the purchase price. The joint life must be a registered
party with a date of birth, and a recorded sex when the form is `BY_SEX`.

The light path: no medical assessment is required on an ANNUITY case. The decision carries a
required **age evidence confirmed** flag (refused without it on an annuity, ignored otherwise).
The two-person decision rule is unchanged. **Acceptance re-runs the quote** and refuses if it
cannot price. ACCEPT auto-issues, as for every product (step 4 L13); the policy is PROPOSED,
`SINGLE`, premium = purchase price.

### 4.3 The contract

A new module **`annuity`**, `allowedDependencies = { "policy::api", "product::api",
"underwriting::api", "party::api", "benefitpayout::api" }`. It reaches claims and billing only by
event envelope, so neither becomes a dependency (the arrangement bonus uses).

On `policy.PolicyIssued`, product is asked first (`resolveAnnuityPlan`); only an ANNUITY version
touches an annuity table. The **contract** is created: policy number, form settings **copied**
from the version, the choice from the case, both lives, purchase price, status
`AWAITING_PAYMENT`.

### 4.4 The lock

On the single premium's `billing.PremiumCollected` for a contract in `AWAITING_PAYMENT`, once:

1. Ages on the collection date, computed exactly as the premium pipeline computes age; sexes
   from party; the joint age difference and its band.
2. The rate cell from the table **in force on the collection date** (its own version's rows —
   the policy's pinned version, step 0 D3).
3. `annual = price × rate ÷ 1000`; `instalment = annual × factor ÷ paymentsPerYear`, rounded
   HALF_EVEN to cents **once**.
4. First due date: collection date + one period (`ARREARS`) or the collection date (`ADVANCE`).
   Guarantee end: first due date + guarantee years (none when 0).
5. The contract stores every input and output — rate, cell, factor, ages, sexes, dates — and
   moves to `IN_PAYMENT`. Locked figures never change.
6. `benefitpayout.openAnnuityStream(...)` (§5.1).

If pricing fails at the lock (it should not, since acceptance priced it with the same rows), the
contract moves to **`LOCK_FAILED`** with the refusal in its own words, shown on the Annuity tab and
logged — never silently swallowed by an AFTER_COMMIT listener (step 2's lesson). No stream opens.
Free-look can still cancel and refund it; any other resolution is staff's and is outside D1.

## 5. Paying it

### 5.1 What `benefitpayout` gains

A new `PayoutKind.ANNUITY` (purpose `ANNUITY_PAYOUT`, added to `PayoutPaymentListener`'s list) and
an **open-ended stream**:

- `openAnnuityStream(policy, firstDue, frequency, baseAmount, escalationPercent,
  proofOfLifeIntervalMonths)` expands 12 months of instalments ahead.
- `AnnuityRollForward` (`@Scheduled`, `initialDelayString` = interval, step 0's lesson) extends
  every open stream whose horizon is near, row-locked, with a unique (stream, due date) so a rerun
  adds nothing.
- Escalation from the locked base each time: `base × (1 + e)^(whole years since first payment)`,
  rounded once per instalment. Rounded figures are never compounded.
- Proof of life, review, two-person approval, payment runs and the register apply unchanged.
- `endStream(policy, afterDate, reason)`: cancels everything due after the date.
- `reduceStream(policy, fromDate, percent)`: re-amounts later instalments to the survivor
  percentage; proof of life now follows the survivor.
- `redirectStream(policy, fromDate, untilDate, payees)`: instalments continue to the
  beneficiaries until the guarantee ends, then the stream ends. Proof of life stops.

### 5.2 Death

A DEATH claim on an ANNUITY policy goes through the normal assessor / decider / evidence flow,
with two changes in claims:

- On a joint form the claim **names which life died**; annuity validates it against the contract.
- The ceiling comes from `annuity::api.deathValue(policy, deceasedLife, dateOfDeath)`: the
  capital-protection refund, or zero. `ceilingFor` (step 4 R1) routes ANNUITY there, so the claim
  screen shows the same figure approval enforces. **A zero ceiling, and a zero approval, are
  allowed for ANNUITY only**: the claim records a verified death and pays nothing.

`annuity` listens for `claims.ClaimApproved` (DEATH) by envelope:

| Who died | Effect | Status |
|---|---|---|
| First of two lives (joint) | `reduceStream` from the first instalment due after the death | `SURVIVOR` |
| Last or only life, inside the guarantee | `redirectStream` to the beneficiaries until the guarantee ends | `GUARANTEE`, then `ENDED` |
| Last or only life, after the guarantee | `endStream` | `ENDED` |

**Capital protection**, on the last death only:
`refund = max(0, price − gross income paid − gross guaranteed instalments still to come)`.
It is the claim's ceiling, so the death claim pays it through its normal settlement — once, by
claims, never by both modules.

**Late notification.** Instalments paid for due dates after the death are an **overpayment**. It is
offset first against guaranteed instalments still to come, then against the refund. Any remainder
is recorded on the contract as owed back. Nothing is clawed back automatically.

### 5.3 Free-look

The existing free-look cancellation. For an annuity the refund is the purchase price less gross
income already paid. The stream ends and the contract becomes `CANCELLED`. With no premium
collected, the contract is simply cancelled.

### 5.4 The policy's own status

The policy stays `ACTIVE` while any income is payable. When the stream ends, annuity asks policy
to end it with a new terminal status, **`ANNUITY_ENDED`** (policy V-next, enum, OpenAPI, console
neutral bucket), and never `EXPIRED`, which means cover ran to term. Free-look keeps its existing
status. The expiry sweep skips ANNUITY policies, which have no maturity date.

### 5.5 Withholding

- **`withholding_rule`** in benefitpayout: payout kinds it applies to, rate %, effective from/to,
  legal reference. Proposed by one finance user and approved by another, as bonus declarations
  are, because a rule moves customers' money to the tax authority and the open audit finding is
  about one person changing a live financial figure. One approved rule per kind per date.
- **Applied at approval**, when the gross is final: `withheld = gross × rate`, HALF_EVEN. The
  instalment stores gross, withheld, net and the rule id; the payment rail is asked for the net.
  With no rule in force nothing is withheld and the instalment records that it checked.
- `benefitpayout.PayoutPaid` gains `grossAmount`, `withheldAmount`, `netAmount` (additive; absent
  fields mean nothing was withheld, so old events still post).
- D1 applies rules to `ANNUITY` only. A rule names its kinds, so extending withholding to maturity
  or survival payouts later is data, not code.

## 6. The ledger

- Premium and instalments post as today.
- An instalment with tax withheld posts **DR benefit expense (gross) / CR cash (net) / CR
  withholding tax payable (withheld)**. A new liability account in the chart, numbered within the
  existing hierarchy (finaccounting V-next).
- Remittance to the tax authority is a later finance piece; until then the liability accumulates
  and is visible.
- No annuity reserve. The purchase price is premium income on the day it is collected — the same
  known gap as savings, owned by the coming IFRS 17 model.

## 7. The console

- **Product publish form:** an Annuity section, only on ANNUITY: timing (empty select, no
  default), proof-of-life interval, joint age-difference range, the forms list (four settings and
  rate basis each), a rate-grid editor per form (the cash-value editor's pattern), frequency
  factors. Every field renders its own error.
- **Case form:** on an ANNUITY product, form, frequency, joint life (party picker) and purchase
  price, with the live quote showing the instalment before the case is opened. The decision panel
  shows "Age evidence confirmed" on an annuity case.
- **Policy page:** an **Annuity** tab, present only when the read is non-null (the Account and
  Bonuses pattern): the locked figures and their rate cell; status; next instalment and next
  escalation; guarantee end; capital-protection balance remaining; any overpayment owed.
- **Payouts register:** gross, withheld and net on annuity instalments.
- **Finance:** a Withholding rules screen — propose, approve, withdraw — built from the bonus
  declarations panel. Gates in the server's words.

## 8. Testing

- **Unit:** rate lookup (sex, age, band edges), frequency factor and rounding, escalation from
  base, refund netting, overpayment offset, first-due and guarantee-end dates for both timings.
- **Integration**, through the real flow: case → accept (quote re-run) → issue → collect → lock →
  stream. Death on each form: life only, 10-year guarantee inside and after, joint first and last
  death, capital protected with and without a guarantee. Free-look before and after collection.
  Late notification. Proof-of-life suspension never ends a contract. Roll-forward exactly once.
  Withholding with and without a rule, and with a rule that starts mid-stream.
- **Contract:** every new endpoint, strict response validation, every new response field
  asserted by name (step 4 L9).
- **e2e:** publish an annuity version, quote and buy, collect, see the Annuity tab and the first
  instalment on the register net of an approved withholding rule. Dates by `todayIso()`.
- One shared migration list for the new classes (step 4 L7). Full suite and full e2e at the gate.

## 9. Not in D1

- D2 vesting from an account, and D3 group pension.
- Reserves and the IFRS 17 measurement of annuities.
- Remitting withheld tax to the authority.
- Enhanced (impaired-life) annuities, and any medical underwriting of annuities.
- Commuting a guarantee to a lump sum (Q7 chose continued instalments).
- Full two-age joint grids (Q3 chose bands).
- Changing the form, frequency or payee rules after purchase.
- A mortality-and-interest engine that generates rate tables.
