# Family funeral cover (FUNERAL category) — design

Date: 2026-10-04. Status: agreed with the user section by section; awaiting review of this document.

## 1. What it is

A protection product: one policy, owned and paid for by a **main member**, covering the main member and
their family. Each covered life has its own benefit, set by the **plan** the customer picked, and its own
premium, set by the plan, the life's **role** and the life's **age band**. A death of any covered life
pays that life's benefit. Nothing accumulates: the premium buys one period's cover.

The user's example: the husband takes the policy and pays yearly; if he dies the family is paid an amount
measured by the band; the wife and children each have their own rates.

**Line of business:** protection. **Category:** a new `FUNERAL`, beside `TERM_LIFE`, not folded into it —
a category gates behaviour across modules, and every rule below would otherwise leak into existing term
products unasked (the same reasoning that kept `CREDIT_LIFE` out of `GROUP_LIFE`).

Group pension (product step 5 D3) is parked by the user and is not part of this work. A group funeral
variant (employer/SACCO) is a later, separate step reusing covered lives.

### Decisions taken (do not drift)

| # | Question | Answer |
|---|---|---|
| Q1 | How is the band chosen? | **Both**: the customer picks a plan (A/B/C → benefits); each life's premium also depends on its own age band |
| Q2 | Roles and limits | Main member, spouse, child, parent, extended; **one spouse**; ages/counts as defaults below, all configurable |
| Q3 | Claim rules, waiting period, payee, after-death, lapse | **All configured per product version by staff**; nothing hard-coded |
| Q4 | Architecture | **A**: covered lives on an ordinary individual policy (not a mini group scheme, not one policy per life) |
| Q5 | Premium form | A **fixed yearly amount per life**, not a rate per mille |
| Q6 | Ageing | Every life **re-priced to its new age band at each policy anniversary**; the plan's benefits do not change |
| Q7 | Endorsements | Staff may add and remove lives **at any time**, effective from the next premium date |

## 2. Product configuration (per version, immutable once published)

### 2.1 Plans
`plan_code` (e.g. `A`, `B`, `C`) with a display name, and a benefit per role. A role with no benefit on a
plan is **not covered by that plan**; an application adding such a life is refused.

### 2.2 Premium table
`(plan_code, role, age_from, age_to) → yearly_premium` (fixed amount, > 0). Monthly/quarterly/half-yearly
instalments use the existing frequency loading.

**Publishing is refused** if, for any plan and any role that plan covers, an age between the role's minimum
entry age and its cover-stop age (or the oldest priced age where cover never stops for age — see 2.3) has
no premium row, or if two rows overlap. A policy must never meet an age it cannot price; this follows the
platform's refuse-rather-than-default rule.

### 2.3 Role rules
Per role: allowed (yes/no), max lives, min entry age, max entry age, cover-stop age (null = never stops for
age). Child only: an extended stop age when marked as a student. Exactly one main member, always allowed.

Defaults offered in the console (staff edit them):

| Role | Max lives | Entry age | Cover stops |
|---|---|---|---|
| MAIN_MEMBER | 1 | 18–65 | never |
| SPOUSE | 1 | 18–65 | never |
| CHILD | 6 | 0–20 | 21 (25 if a student) |
| PARENT | 4 | up to 75 | never |
| EXTENDED | 4 | up to 65 | never |

Where cover never stops for age, the premium table must still price every age a life can reach; the
publish check uses a version-level `max_priced_age` (default 100) as the upper bound for those roles.

### 2.4 Claim rules
- `waiting_period_months` (null = none) for natural death; `accident_waives_waiting` (boolean).
- `dependant_claim_payee`: `MAIN_MEMBER` or `MAIN_MEMBER_BENEFICIARY`.
- `on_main_member_death`: `POLICY_ENDS` or `SPOUSE_TAKES_OVER`, plus `free_cover_to_paid_date` (boolean)
  layered on either.
- The existing suicide and pre-existing-condition exclusion windows remain available unchanged.

### 2.5 Missed premium
The version's existing `grace_period_days`, applied to the whole policy. A lapse lapses every life.

## 3. The policy and its covered lives

### 3.1 At sale (underwriting case)
- The main member is the policyholder **and** the life assured, a registered party with KYC as today.
- The case records the chosen plan and each dependant: role, full name, date of birth, sex, optional ID
  number, and (children) a student flag.
- Dependants are **not** registered as parties (the `FREEFORM` pattern from credit life): a name on the
  policy, promoted to a party at claim.
- The application is validated against the version's role rules (counts, entry ages, roles the plan does
  not cover) with a specific message per failure, e.g. "Plan A does not cover parents".
- The quote returns one line per life (role, age, benefit, yearly premium, instalment) and the total.

### 3.2 At issue
- One `covered_life` row per person: role, name, date of birth, sex, benefit, yearly premium, age band
  priced at, `cover_start`, status `ACTIVE | ENDED`, `end_reason`, `ended_on`, nullable `party_id`.
- Policy premium = **sum of ACTIVE lives' premiums**; billing is unchanged (one invoice per period).
- Each life's waiting period runs from **its own** `cover_start`.

### 3.3 Endorsements
- **Add life:** validated against the same rules as at sale (age at the effective date); covered from the
  next premium date with its own waiting period; premium rises.
- **Remove life:** cover ends at the next premium date; premium falls; reason recorded.
- **Ageing out:** a nightly sweep ends any life past its role's stop age (the student age for a child
  marked as a student), with `end_reason = AGED_OUT`; premium falls from the next premium date.
- **Anniversary re-pricing:** on each policy anniversary every ACTIVE life is re-priced at the age band its
  age now falls in; benefits are unchanged. Mid-year birthdays never move a band.
- Every premium change restates the billing schedule from the next premium date and is published as an
  event the communication module turns into a notice.

## 4. Claims

- A death claim on a `FUNERAL` policy **must name an ACTIVE covered life**; a name-only life is promoted to
  a party from the death certificate first (existing credit-life step).
- Assessment, in order, all read from the version:
  1. the life was covered on the date of death (past `cover_start`, not ended);
  2. waiting period from that life's `cover_start` — inside it the claim is declined with the reason,
     unless the death was accidental and the version waives accidents;
  3. the policy was in force on the date of death (grace-period deaths pay net of the unpaid premium, as
     today; lapsed → declined);
  4. suicide / pre-existing exclusions where configured.
- **Amount:** the benefit stored on the covered life, never re-read from the plan.
- **Payee:** a dependant's death pays per `dependant_claim_payee`; the main member's death pays the
  policy's beneficiaries, as today. Payout and GL flow are unchanged.
- **After payment:**
  - dependant: that life ENDED (`DECEASED`), premium falls from the next premium date, policy continues;
  - main member: `POLICY_ENDS` ends every life (or keeps them covered to the paid-to date when free cover
    is on); `SPOUSE_TAKES_OVER` makes the spouse the policyholder (registered as a party if not already)
    and the policy continues at the next premium.
- One death claim per covered life (the existing credit-life guard, keyed on the covered life).
- A declined or withdrawn claim leaves the life ACTIVE.

## 5. Screens and other modules

**Staff console**
- Publish version: a FUNERAL section with the plans grid, premium table (with CSV upload), role rules,
  claim rules; publish refusals shown per gap.
- Open application: plan picker, lives added row by row, live quote with per-life lines and total.
- Policy record: a **Covered lives** tab (role, name, age, benefit, premium, cover start, waiting period
  ends, status) with Add life / Remove life.
- Claim registration: a "Who died?" picker of ACTIVE lives on a FUNERAL policy.

**Agent console:** the same application screen.

**Other modules:** billing — no new logic, the premium changes through restatement; commission — on the
total premium as usual; regulatory reporting — lives covered and sums assured per life alongside policy
counts; communication — life added, life ended, premium changed at anniversary.

## 6. Testing and build order

Backend tests: publish refuses gaps and overlaps; per-role application rules; premium = sum of lives;
waiting-period decline and accident waiver; a dependant claim keeps the policy in force; both
main-member-death rules with and without free cover; ageing out; anniversary re-pricing; one claim per
life; a declined claim leaves the life active.

Seed: dev product `FUN-FAM-01` "Nlolo Familia" with plans A and B.

E2E: publish the product; sell a family of five; add a baby; pay; claim for a child (paid, policy
continues); a claim inside the waiting period (declined).

Build order: (1) product configuration, (2) application and quote, (3) issue and covered lives,
(4) endorsements, ageing out, re-pricing, (5) claims, (6) console, (7) seed and e2e, (8) full gate.

## 7. Out of scope

Group funeral cover (employer/SACCO); cash-back or premium-refund riders; repatriation or grocery benefits;
group pension (parked).
