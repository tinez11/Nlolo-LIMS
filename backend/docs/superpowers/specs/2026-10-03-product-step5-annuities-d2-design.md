# Product step 5 / D2 — deferred annuities and pension vesting

Status: design approved section by section, 2026-10-03. Builds on D1 (immediate annuities,
`2026-10-03-product-step5-annuities-d1-design.md`) and step 3 (accumulation).

## 1. What D2 is

A **deferred annuity** is one policy in two phases. It **accumulates** in a step 3 account —
contributions, interest at a declared rate over a guarantee, charges — and on its **vesting date**
the same policy turns from saving to paying: the account closes, an optional **lump sum**
(commutation) is paid as cash, and the rest of the balance **buys a D1 annuity**, locked on that
policy at the rates in force that day. One policy number and one history, from first contribution
to last instalment.

The D1 spec's sub-project table already names it: *"an account policy's balance (less any commuted
lump sum) buys a D1 annuity at its vesting date."* D3 (group pension) follows D2 and is out of scope.

## 2. The user's decisions (2026-10-03) — do not drift

| # | Question | Decision |
|---|----------|----------|
| Q1 | One policy or two | **One policy, two phases.** The form is chosen near vesting, not at sale. |
| Q2 | Vesting date | **A target date plus a window.** A retirement age chosen at sale gives the target; the version states the window (`minVestingAge`..`maxVestingAge`) inside which staff may vest early or defer. |
| Q3 | Lump sum | **Up to a version cap.** `maxCommutationPercent` (0 = none); the customer chooses any percentage up to it. The cash is its own payout kind, `COMMUTATION`, so withholding can treat it separately from annuity income. |
| Q4 | No instruction | **The version names a default.** With no instruction the policy vests on its target date into the version's default form and frequency, **with no lump sum**. The default form may not be joint. |
| Q5 | Premiums when deferred | **The person recording a deferral chooses** whether contributions continue to the new date or stop at the original target date. Without a deferral, contributions end at the target date (it is the premium-paying term). |
| Q6 | Death before vesting | **The balance goes to the beneficiaries** through the existing account death closing. No annuity is created. |
| Q7 | Rates at vesting | **The rates in force on the vesting date**, from the product's current version — as D1 locks "at the rate in force that day". Forms are chosen from the current version's list. |
| Q8 | Proof of age | **Confirmed at sale, re-confirmed at vesting only if the party's date of birth or sex has changed** since that confirmation. |
| Q9 | Surrender before vesting | **The version decides** (`surrenderBeforeVesting`): a locked pension refuses surrender and withdrawals; an unlocked deferred annuity allows them exactly as step 3 does. |
| — | Architecture | **Option 1: the annuity module owns vesting.** Accumulation gains one way to close (`VESTED`); benefitpayout gains one payout kind (`COMMUTATION`). |

Defaults accepted with the design (correct them at spec review if wrong):
- Free-look applies at **sale only**, as for any account policy; there is no cooling-off at vesting.
- Early vesting and deferral are recorded by staff inside the window. A joint life is chosen at
  vesting and must be a person with a recorded date of birth.
- Reminders: one SMS each at **90 and 30 days** before the target date, through the communication module.

## 3. The product version

A deferred annuity is an **ANNUITY product whose version carries an account**. Today both
`AccumulationPlanValidator` (step 3 Q9, "a pension must not be sellable before it can vest") and
`AnnuityPlanValidator.checkExclusions` refuse that combination; D2 lifts both refusals **only for a
version that also carries vesting terms**. An ANNUITY version is therefore one of two kinds:

| Kind | Account | Annuity terms | Vesting terms |
|------|---------|---------------|---------------|
| Immediate (D1) | none | required | refused |
| Deferred (D2) | required (`ACCOUNT` basis) | required | required |

The three blocks of a deferred version:

- **Account terms (step 3, unchanged):** guaranteed rate, minimum balance, charges by policy year.
  Never a fixed-term deposit (`DEPOSIT`), never with-profits, never a payout schedule. **Pinned** to
  the version the policy was sold on.
- **Annuity terms (D1, unchanged):** forms, rate grid, frequencies, timing, basis, proof-of-life
  interval. **Read from the product's current version at vesting** (Q7) — on a deferred policy the
  sold version's annuity terms are only ever a preview.
- **Vesting terms (new, on the next free product migration):**
  - `minVestingAge`, `maxVestingAge` — whole years.
  - `defaultFormCode`, `defaultFrequency`.
  - `maxCommutationPercent` — 0 to 100, decimal.
  - `surrenderBeforeVesting` — boolean, no default (the version must say).

New validator rules (`VestingPlanValidator`, mirrored by the console message for message):
- Vesting terms are only for an ANNUITY product, and only with an `ACCOUNT` basis; an ACCOUNT
  ANNUITY version must carry them.
- `minVestingAge` ≤ `maxVestingAge`, both within 0..120.
- `defaultFormCode` names one of the version's forms, and that form is **not joint**.
- `defaultFrequency` is one of the version's frequencies.
- `maxCommutationPercent` between 0 and 100.
- `surrenderBeforeVesting` stated.
- **Coverage walks the vesting window, not the entry ages.** A deferred version may sell from 18
  to 55 and vest from 55 to 70; every form must have a rate (both sexes on BY_SEX, every age
  difference on a joint form) for every age `minVestingAge`..`maxVestingAge`. Immediate versions
  keep checking entry ages, unchanged.
- The entry-age bounds are still required (they bound the sale), and `maxEntryAge` must be below
  `maxVestingAge` — a customer must be able to reach a vesting age.

## 4. The sale

The underwriting case on a deferred version records a **retirement age** instead of a form choice.
`annuity_choice` is not used. A new table, `underwriting.deferred_annuity_choice`, keyed by
`case_id`, holds the retirement age and, at acceptance, the age-evidence confirmer, time, and the
confirmed date of birth and sex — following D1's R8 ("no new column on the case table").

- The retirement age must give a target date inside the window and after the applicant's entry age.
- **Acceptance** takes the D1 light path: ACCEPT or DECLINED only, and ACCEPT needs proof of age
  confirmed. The confirmation stores the **date of birth and sex that were checked** (Q8).
- Acceptance **auto-issues an account policy**, not a single premium: the proposal's premium and
  frequency; term and premium-paying term running to the **target date**, which becomes the
  maturity date. The step 3 account opens as for any account policy.
- The annuity module creates the **contract** at issue with status **`ACCUMULATING`**, carrying the
  target date, the window (copied from the sold version), and the confirmed date of birth and sex.
  No form, no lock.

## 5. The saving phase

- **Contributions, interest, charges:** step 3, unchanged.
- **Surrender and withdrawals:** when the sold version's `surrenderBeforeVesting` is false,
  accumulation refuses both with a message naming the rule ("This pension cannot be surrendered or
  withdrawn from before it vests"). When true, both work exactly as step 3; a full surrender moves
  the contract to `CANCELLED` (reason recorded).
- **Death before vesting (Q6):** the existing account death closing pays the balance through the
  death claim. The contract moves to `ENDED` with "died before vesting" recorded; no annuity is created.
- **Free-look:** the existing account free-look; the contract moves to `CANCELLED`.
- **Reminders:** a daily pass sends one SMS at 90 days and one at 30 days before the vesting date
  ("Your pension starts on {date}. Contact us to choose how it is paid."), each recorded so it is
  sent once; a deferral re-arms them for the new date.

Contract statuses become: `ACCUMULATING` → (`IN_PAYMENT` → `SURVIVOR` / `GUARANTEE` → `ENDED`), or
`ACCUMULATING` → `ENDED` (died before vesting) / `CANCELLED` (free-look, surrender). D1's
`AWAITING_PAYMENT` and `LOCK_FAILED` remain for immediate annuities.

## 6. Vesting

### 6.1 The instruction

Recorded on the policy's Annuity tab by any staff member who services policies; changeable until
the contract vests. One current instruction per contract; each change is kept with who and when.

- **When:** the target date (default); an **earlier** date — not in the past, and the annuitant's
  age on it at least `minVestingAge`; or a **deferral** to a later date — the annuitant's age on it
  at most `maxVestingAge`. A deferral records **contributions continue | stop** (Q5): it moves the
  policy's maturity date, and when contributions continue it also moves the premium-paying term
  (billing extends); when they stop, the premium-paying term stays at the original target date.
  An early vesting date earlier than the premium-paying term's end also ends billing then.
- **How:** a form and frequency from the **current** version's lists; a joint life when the form
  is joint (a person, with a date of birth, other than the annuitant); a **lump-sum percentage**
  from 0 up to the sold version's `maxCommutationPercent`. A percentage, not an amount, because the
  balance moves until the day.
- **No instruction:** target date, the sold version's `defaultFormCode` and `defaultFrequency`, no
  lump sum (Q4). If the default form is no longer offered by the current version at vesting, that
  is a pricing refusal (§6.3), not a silent substitute.

### 6.2 The vesting sweep

Daily (interval property, short under `local`), over contracts `ACCUMULATING` whose vesting date
is on or before today (civil date, `Africa/Dar_es_Salaam`). Each policy in its own transaction
through the envelope-runner pattern; the sweep never rethrows. In order:

1. **Age check (Q8).** If the party's date of birth or sex differs from the confirmed pair, hold:
   "Age re-confirmation needed: the date of birth or sex on record has changed since it was confirmed".
2. **Price first.** The product's current version prices the annuitant's age on the vesting date
   (and the joint life's), for the chosen or default form and frequency, with a provisional price
   of the current balance. Any `AnnuityPricingRefusedException` holds the policy with the pricer's
   words. **Nothing has been touched.**
3. **Close the account** — `AccumulationApi.closeForVesting(policyNumber, vestingDate)`: interest
   credited to the vesting date, the account closed with reason `VESTED`, balance B returned.
4. **Split:** lump sum = B × percentage / 100, rounded once HALF_EVEN to the cent; purchase price =
   B − lump sum.
5. **Lump sum:** a `COMMUTATION` instalment due on the vesting date, payee the policyholder, through
   the normal review and two-person approval. Withholding applies when an approved rule names
   `COMMUTATION`. Proof of life is never required for it.
6. **Lock** the contract from the purchase price at the current version's rates, exactly as D1's
   lock at collection (the same `AnnuityPrice`, locked figures immutable by trigger), and open the
   income stream; first due per the current version's timing. Contract → `IN_PAYMENT`. Remaining
   premium invoices are cancelled; the policy stays in force, now paying.

Steps 3–6 commit together or not at all: there is never a closed account without an annuity.

### 6.3 Holds

A held contract stays `ACCUMULATING` with a hold reason and time; it keeps earning interest and is
retried on every sweep, so nothing is lost by waiting. Holds clear by: re-confirming age (records a
new confirmation of the current date of birth and sex), changing the instruction (another form,
frequency or joint life), or a later product version that can price it. A failure inside steps 3–6
rolls back and is recorded on the contract the same way.

### 6.4 Ledger

One new finaccounting posting rule on the vesting event (accounts confirmed against the chart in the plan):
- DR policyholder account liability — B
- CR annuity premium — purchase price (as D1 books a single premium)
- CR benefits payable — lump sum

The lump sum's payment then posts like any payout: DR gross, CR cash net, CR 2230 tax.

## 7. Architecture

- **annuity** (owns D2): vesting terms read via `ProductApi`; the instruction and hold on its own
  tables (annuity V2); the vesting sweep; the reminders trigger (an event the communication module
  consumes, as offers and cover notices are). Calls `AccumulationApi.closeForVesting`,
  `BenefitPayoutApi` (commutation instalment, stream), its own D1 lock.
- **product:** vesting terms (V-next), `VestingPlanValidator`, coverage over the window, the two
  validator relaxations, `resolveVestingPlan(versionId)`.
- **underwriting:** the retirement age on a deferred case; acceptance stores the confirmed date of
  birth and sex.
- **policy:** the issue listener's deferred branch (account policy to the target date);
  `restateMaturity` / premium-paying-term changes for deferral and early vesting.
- **accumulation:** `closeForVesting`; surrender and withdrawal refused when the version is locked.
- **benefitpayout:** `PayoutKind.COMMUTATION` (refused as an authored row), never needing proof of life.
- **finaccounting:** the vesting posting rule.
- **communication:** the two reminder templates.

## 8. Console

- **Publish form:** on ANNUITY, a choice between immediate and **deferred (vests from an
  account)**. Deferred shows the existing account section plus a **Vesting** section (window,
  default form and frequency, lump-sum cap, locked-before-vesting); every check mirrors
  `VestingPlanValidator`.
- **Open case:** on a deferred version, **Retirement age** replaces the form choice and the target
  date it gives is shown; the decision panel keeps the proof-of-age checkbox.
- **Annuity tab, saving phase:** target or instructed vesting date, window, current balance (from
  the account), what it will vest into, any hold with its reason, and actions — **Record vesting
  instruction**, **Defer**, **Re-confirm age** (only when held for it). After vesting it is D1's tab.
- **Held vestings:** a queue under Policies of held contracts, each with what clears it.
- **Payouts:** `COMMUTATION` labelled "Pension lump sum".

## 9. Failure handling

- Every refusal is a 422 in the rule's own words; the console mirrors the words it can check first.
- Pricing and age holds leave the account untouched and retry daily.
- Steps 3–6 are one transaction; a failure rolls back and is recorded on the contract.
- The sweep and the reminder pass never rethrow, and both join the scheduled-jobs health check
  (health DOWN when not installed), as every sweep does.

## 10. Testing

- Unit: `VestingPlanValidator` rule by rule; coverage over the vesting window; the lump-sum split
  and its rounding.
- Integration: sale → `ACCUMULATING`; default vesting on the target date; early vesting with a 25%
  lump sum (balance split, commutation payout, lock at the current version's rates — a new version
  published after the sale prices it); deferral with contributions continuing and with them
  stopping (billing asserted both ways); the age-mismatch hold and re-confirmation; a pricing-refusal
  hold leaving the account intact; death before vesting; a locked pension refusing withdrawal and
  surrender; free-look; the ledger posting; reminders sent once each.
- Contract tests for every new endpoint against the spec.
- E2E: publish a deferred product, sell it, record an early vesting with a lump sum, let the sweep
  vest it, see income on the Annuity tab and the pension lump sum in Payouts.

## 11. Out of scope

Group pension (D3); a guaranteed minimum death benefit; a spouse taking the pot as their own
annuity; guaranteed annuity rates or best-of rates; transfers in from another provider; unit-linked
funds; IFRS 17 reserving (as D1).
