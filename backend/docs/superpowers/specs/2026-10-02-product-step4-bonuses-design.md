# Product step 4 — with-profits bonuses (sub-project C)

**Status:** design agreed 2026-10-02. Next: implementation plan.
**Builds on:** step 0 (rolling billing, EXPIRED at term end, pinned version), step 1 (cash-value
scale, surrender, paid-up, loans), step 2 (payout engine, `benefitpayout`), step 3 (accumulation,
merged at `5fc2c8f8`).

## 1. Where this sits

The guide's §8 (participating / with-profits) asks for a bonus declaration batch, simple, compound
and terminal bonuses, and a bonus history, all fed into maturity, death and surrender values. Today
nothing on the platform can express a bonus. Batch 2b removed `SUM_ASSURED_PLUS_BONUS` on purpose
because nothing could compute it.

| Sub-project | Subject | State |
|---|---|---|
| A | Payout engine | merged (step 2) |
| B | Accumulation | merged (step 3) |
| **C** | **Bonuses** | **this spec** |
| D | Annuities | next |
| E | Unit-linked | later |
| F | Riders | any time |

## 2. Decisions

| # | Question | Decision |
|---|---|---|
| Q1 | Bonus types | **Reversionary and terminal.** The version chooses `SIMPLE` (rate × sum assured) or `COMPOUND` (rate × (sum assured + bonuses already attached)). |
| Q2 | How bonuses are declared | **Per product, per valuation date**: one reversionary rate and one terminal rate. Proposed by one person, approved by another, as step 3's rate declarations are. |
| Q3 | Eligibility | **Its own rule, separate from "in force".** A policy receives a declaration when all three hold: (a) it was issued under a participating version on or before the valuation date; (b) its status on the valuation date was ACTIVE, REINSTATED or SUSPENDED; (c) if its status that day was PAID_UP, the version's `paidUpParticipates` rule allows it. That rule is false unless authored. Full rate, no pro-rating. |
| Q4 | Part of a year | **An interim bonus at a death or maturity exit**, only if the policy is eligible at exit under Q3's rule. Amount: the last declared reversionary rate × whole months since the last valuation date ÷ 12, on the Q1 base. |
| Q5 | Terminal bonus | **A % of attached reversionary bonuses**, at the terminal rate of the latest approved declaration on or before the exit date. Paid on death and maturity only, never on surrender. |
| Q6 | Surrender value of attached bonuses | **Stated explicitly by the version, with no default:** `NONE` (they add nothing), `SUM_ASSURED_SCALE` (step 1's cash-value factor for the year, explicitly chosen), or `OWN_SCALE` (per-mille rows by policy year). No terminal bonus. |
| Q7 | Retention | **Attached bonuses are always kept.** No forfeiture mechanism, since no contract here asks for one. Paid-up: kept, no new declarations (unless Q3's rule says otherwise). Lapsed: kept, nothing new while lapsed. Reinstated: kept, eligible again from the first valuation date on or after reinstatement. |
| Q8 | Bonus options (cash, premium reduction) | **Deferred.** Bonuses attach only. |
| Q9 | Categories | **ENDOWMENT and WHOLE_LIFE.** A flag on the version, and it is exclusive with step 3's `ACCOUNT` value basis. A participating ENDOWMENT must have a MATURITY payout row (step 2), or its bonuses could never be paid at term end. |
| Q10 | General ledger | **Unchanged**, as step 3. IFRS 17 owns it later. |

Two consequences of these decisions, agreed when the design was approved:
- A paid-up policy at maturity or death still gets the terminal bonus on its retained bonuses, since Q5 applies to whatever is attached.
- A paid-up policy gets an interim bonus only when Q3's rule makes it eligible.

## 3. Ownership

A new module `bonus`, with `allowedDependencies = { "policy::api", "product::api" }`.

- It owns declarations, the attachment ledger, exit settlements and its own status timeline.
- `benefitpayout` gains `bonus::api`: the maturity payment and the death limit both add the bonus.
- Claims already reaches the death limit through `benefitpayout`, so claims needs no new dependency.
- Policy cannot call `bonus`, because that would be a cycle. After every attachment or reversal,
  `bonus` calls `policyApi.restateAttachedBonus(policyNumber, total)`. Policy's surrender quote
  applies the version's Q6 rule to that projection itself, since it already reads the product's
  scales.

The user's three rules from step 3 carry over unchanged:
- `bonus` owns the attached-bonus balance and every movement in it.
- Entries are immutable; corrections are REVERSAL entries.
- One source reference makes at most one entry.

## 4. Data model

### 4.1 `bonus.declaration`

| Column | Notes |
|---|---|
| `declaration_id`, `tenant_id`, `product_id` | |
| `valuation_date` | The date eligibility is judged on, which may be before or after approval. |
| `reversionary_rate_percent`, `terminal_rate_percent` | Both `>= 0`, both NUMERIC(7,4). |
| `status` | PROPOSED, APPROVED or WITHDRAWN. |
| `proposed_by`, `approved_by`, timestamps | A CHECK requires `approved_by <> proposed_by`. |
| `attached_through` | Nullable. Set when the drain has finished this declaration. |

A partial unique index allows one APPROVED declaration per (tenant, product, valuation_date).

### 4.2 `bonus.attachment_entry` — the ledger

Columns: `entry_id`, `tenant_id`, `policy_number`, `seq`, `entry_type` (REVERSIONARY or
REVERSAL), `amount`, `total_after`, `declaration_id` (null for a reversal), `basis_amount` (the
sum assured, plus attached bonuses if COMPOUND), `rate_percent`, `source_type`, `source_ref`,
`reverses_entry_id`, `created_by`, `created_at`.

These guarantees are enforced in the database, exactly as step 3's ledger:
- grants plus a trigger refuse UPDATE and DELETE, even by the owner;
- a follows-trigger requires `total_after = previous + amount` and a contiguous `seq`;
- `total_after >= 0`;
- `ux_attachment_source (tenant, source_type, source_ref)` makes the source unique. A declaration's
  source is `declaration:<id>`, so one declaration attaches to a policy once.

### 4.3 `bonus.settlement`

One row per exit: `exit_type` (MATURITY or DEATH), `exit_ref` (a maturity instalment id or a claim
id), `exit_date`, `attached_amount`, `interim_amount`, `terminal_amount`, `terminal_rate_percent`
and `declaration_id`. It is append-only, with a unique index on (tenant, exit_type, exit_ref).

### 4.4 `bonus.status_event` — the status timeline

Policy keeps no status history; it stores only `lapsed_at`. Q3 and Q7 need the status on a past
valuation date, so `bonus` records one row per status-changing event: `policy_number`, `status`,
`effective_at`, and `event_id`, which is unique so a redelivered event is ignored. It also records
`product_version_id` and `sum_assured`, the latter restated on paid-up.

The events it consumes are PolicyIssued, PolicyActivated, PolicySuspended, PolicyResumed,
PolicyLapsed, PolicyReinstated, PolicyMadePaidUp, PolicySurrendered, PolicyMatured, PolicyExpired
and PolicyCancelledFreeLook.

The status on a date D is the latest row with `effective_at` on or before the end of D, in
Dar es Salaam civil time. A UTC day would misdate any event between 00:00 and 03:00 local, the
bug step 3 fixed in `benefitpayout`. Policies issued before
this step have no rows. They are not participating anyway: no version has been participating
until now, so no back-fill is needed.

### 4.5 Product, version terms (product V20)

`version_bonus_terms`:
- `participating`;
- `bonus_method` (SIMPLE or COMPOUND);
- `paid_up_participates` (default false);
- `surrender_basis` (NONE, SUM_ASSURED_SCALE or OWN_SCALE), with no default.

`version_bonus_surrender_row`: `policy_year`, `per_mille`, used only for OWN_SCALE.

`BonusPlanValidator` refuses:
- a participating version outside ENDOWMENT and WHOLE_LIFE;
- one that is also ACCOUNT-basis;
- a missing surrender basis;
- SUM_ASSURED_SCALE without a step 1 cash-value scale;
- OWN_SCALE without rows;
- a participating ENDOWMENT with no MATURITY payout row.

## 5. How bonuses move

### 5.1 Declaring

`POST /products/{id}/bonus-declarations` proposes a declaration, and `/bonus-declarations/{id}/approve`
or `/withdraw` decides it. Approving needs a different person; only a PROPOSED declaration can be
withdrawn.

### 5.2 Attaching: the declaration drain

A scheduled drain, modelled on step 3's MonthEndDrain, picks up APPROVED declarations whose
`valuation_date` is today or earlier and whose `attached_through` is null. It attaches them in
batches of policies, each in its own transaction.

For each policy on any participating version of the product, judged on the status timeline as at
the valuation date under Q3, it computes the amount:
- SIMPLE: rate × sum assured.
- COMPOUND: rate × (sum assured + `total_after` as at the end of the day before the valuation date).
- The sum assured is the one in force on the valuation date.
- Rounded once, HALF_EVEN, to 2 dp.

A zero amount writes nothing. The unique source makes a re-run harmless, which also makes the
drain safe to rerun after a crash. `attached_through` is set when no eligible policy remains.

### 5.3 Exits

| Exit | What happens |
|---|---|
| **Maturity** | `benefitpayout.fallDue`, for a MATURITY instalment on a participating version, calls `bonusApi.settleMaturity(policy, instalmentId, dueDate)`. The instalment pays the row amount plus attached + interim + terminal. The settlement row is the record, and the instalment id makes it once-only. |
| **Death** | The 3-arg `deathBenefitCeiling` adds `bonusApi.valueAtDeath(policy, dateOfDeath)` (attached as at death, plus interim, plus terminal). Claims shows the assessor the breakdown, so a death approved at the sum assured cannot silently leave the bonus unpaid. On `claims.ClaimApproved`, `bonus` records the settlement against the claim id. |
| **Surrender** | Policy's quote adds the attached projection × the Q6 basis for the policy year. Nothing is recorded in `bonus`, because nothing is paid out of the bonus balance separately. The status timeline closes on PolicySurrendered. |
| **Free-look** | Every attachment is reversed (`REVERSAL`, source `freelook:<policy>`). In practice this is almost always nothing, since the free-look period is shorter than a declaration cycle. |
| **Lapse, expiry** | Attachments are kept. Expiry ends the timeline. |

## 6. Console

- **Product page:** a bonus declarations panel on the RateDeclarationsPanel pattern (propose,
  approve, withdraw), with the gates in a `bonusGates.ts`. The publish form gains a with-profits
  section for method, paid-up rule, surrender basis and own-scale rows, validated as §4.5.
- **Policy:** a Bonuses tab with the attachment history in sequence (a reversal names what it
  reverses), the attached total and any settlement. It is shown only for a participating version.
- **Figures:** the surrender quote, the maturity instalment and the claim's death value each show
  the bonus as its own line.

## 7. Changes to existing modules

| Module | Change |
|---|---|
| product | V20 bonus terms, `BonusPlanValidator`, `resolveBonusPlan(versionId)`, the request and OpenAPI. The publishVersion overload guard is raised again. |
| policy | `restateAttachedBonus`, which needs a new `attached_bonus_amount` column on `policy_account` (policy V-next). The surrender quote applies Q6. |
| benefitpayout | Depends on `bonus::api`. Maturity adds the bonus; the 3-arg death limit adds `valueAtDeath`. |
| claims | The death value breakdown is exposed for the assessor, read through `benefitpayout`. |
| registries | `migrate.sh` MODULES, the CI loop and `MigrationScriptCoverageTest` gain `bonus`, plus asyncapi for any new event. |
| seed | One participating endowment, `WP-ENDOW-01`, in `seed-dev-data.sh`. |

## 8. Testing

- `BonusLedgerImmutabilityTest` (plain JDBC): no update or delete; the totals must follow; a
  declaration attaches once; every bonus table has RLS; another tenant sees nothing.
- `BonusArithmeticTest`: simple vs compound over three declarations; HALF_EVEN; interim months;
  the terminal rate.
- `EligibilityIntegrationTest`:
  - paid-up with the rule off and on;
  - lapsed on the valuation date;
  - reinstated before and after it;
  - issued after it;
  - suspended;
  - a late-approved declaration with a past valuation date;
  - a redelivered status event.
- `DeclarationIntegrationTest`: two-person approval; one APPROVED per date; the drain re-run.
- `BonusExitIntegrationTest`: the maturity instalment amount; the death limit at the date of death;
  the surrender quote under all three Q6 bases; the free-look reversal.
- `BonusPlanValidatorTest`, covering every refusal in §4.5.
- Frontend unit tests for the panel, tab and gates, plus `e2e/staff-with-profits.spec.ts`: declare,
  approve as a second person, see the attachment on the policy's Bonuses tab, and see the bonus
  line on the surrender quote.

## 9. Out of scope, on the record

- Bonus options: cash, premium reduction, bonus surrender on its own (Q8).
- Forfeiture of attached bonuses (Q7).
- Market-value adjustments and smoothing.
- An actuarial asset share. Rates are declared, not computed.
- The general ledger (Q10).
