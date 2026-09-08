# Proposal capture: term, frequency and beneficiary nominations

**Status:** approved, ready for implementation
**Follows:** `2026-09-08-underwriting-decision-and-single-issuance.md` (Stage 1)

## The problem

Manual issue is the only screen on this platform that can produce a complete policy.

| | Open a case (proposal) | Manual issue |
|---|---|---|
| Applicant / policyholder, life assured | ✓ | ✓ |
| Product, sum assured, currency | ✓ | ✓ |
| Agent of record | ✓ | ✓ |
| Branch, source of business | ✓ | — |
| Proposed commencement date | API only, no form field | ✓ |
| **Premium amount + frequency** | ✗ | ✓ |
| **Policy term / premium-paying term** | ✗ | ✓ |
| **Beneficiaries** | ✗ | ✓ |

So a policy issued on the normal path — the one the design docs call the common case — carries no
term, no premium-paying term, no maturity date (it is derived from commencement plus term) and no
beneficiaries. Not because the listener drops them, as it did for life assured and commencement
before Stage 1's Task 7, but because nobody ever collects them.

That is very likely why staff reach for manual issue. It is not a shortcut; it is the only screen
that captures the whole contract. Stage 1 made one case issue exactly one policy — if the complete
record still requires the manual path, the constraint just makes the workaround harder rather than
unnecessary.

### The sharper consequence: an unchecked term

A product version carries `EligibilityBounds`, including `minTermMonths` and `maxTermMonths`. Those
bounds **are** enforced — by `issueGates` on the manual issue form, against a term typed there.

The case has no term. So on the normal path a risk is assessed, decided and issued without the
product's own term rules ever being consulted. The check exists and sits downstream of the decision,
on a screen the automatic path never visits. An underwriter today accepts a term nobody has stated.

## What the decision actually uses (for context)

Worth stating, because it is easy to conflate three different product artifacts:

- **The underwriting decision** is computed from the product's **rating table** — an AGE multiplier
  and a SUM_ASSURED_BAND multiplier — combined with the highest risk score across the latest
  assessment of each type. Nothing else. Not term, not frequency, not sex, not smoker status.
- **The base rate table** (age band × sex × smoker status → rate per mille) is for pricing, and is
  not an input to the decision.
- **Automatic issuance** prices from a single reference-data value,
  `TZ_BASE_PREMIUM_RATE_PER_MILLE` — one global rate for every product — and does not read the
  per-product base rate table at all.

That last point is a real defect. It is **out of scope here** and recorded below, because it is
pricing rather than capture, and folding it in would make this piece two things at once.

## The fixes

### 1. The case records what the applicant states

`underwriting/V6` adds to `underwriting_case`:

- `requested_term_months INTEGER`
- `premium_paying_term_months INTEGER`
- `premium_frequency VARCHAR(20)`

And a new `underwriting.proposal_beneficiary` table mirroring `policy.beneficiary`'s shape exactly:
`beneficiary_type` (PARTY/FREEFORM), `party_id`, `freeform_designee`, `share_percent`, `revocable`,
and the same `chk_..._exactly_one_designation` CHECK. It must be its own table in the underwriting
schema — `policy.beneficiary` is keyed by `policy_number`, which does not exist at proposal time,
and no cross-schema write is permitted.

All four are **optional**. A product that does not term genuinely has no term, and a proposal with
the nomination left blank is ordinary. But if any beneficiary rows are supplied, their shares must
total 100 — the same rule `validateAndBuildBeneficiaries` already applies on the policy side.

The open-case form grows the fields, and reuses the existing `BeneficiaryRow` component rather than
introducing a second nomination editor.

### 2. Underwriting checks the term against the product

`openCase` validates `requestedTermMonths` against the eligibility bounds on the case's
`productVersionId`, and refuses a term outside them.

At `openCase` rather than at `decide`, because that is where the proposal is recorded and where the
applicant can still be told. The case already locks `productVersionId`, so the bounds cannot move
underneath it.

### 3. The listener carries them through

`UnderwritingDecisionEventListener` passes the term, premium-paying term and beneficiaries from the
case into `IssueRequest` — the same shape and the same reasoning as Stage 1's Task 7, which stopped
it discarding the life assured and the proposed commencement date.

**The premium frequency needs care.** The annual premium is currently divided by 12 unconditionally
and the frequency is hardcoded `"MONTHLY"`. If the frequency becomes captured but the arithmetic
does not follow, a quarterly-paying applicant receives a monthly figure — a new defect introduced by
this change. The division must use the captured frequency's periods per year.

### 4. Manual issue becomes a fuller review

The prefill from a selected case already covers policyholder, life assured, product and commencement
date. Extend it to term, premium-paying term, frequency and beneficiaries, all still editable. The
manual form then reads as "confirm or override what the proposal says" rather than as a parallel
data-entry screen that can silently disagree with the case it claims to come from.

## Deliberately out of scope

- **The quoted premium.** Recording a price on the case is halfway to the offer/acceptance stage,
  and half of that stage is worse than none — the field would mean two things. Premium stays
  computed on the automatic path and typed on the manual one.
- **Offer, acceptance and first premium.** Still the largest divergence from the researched flow: a
  policy goes in force before the customer has agreed a price or paid anything.
- **Per-product base rates in issuance.** Named above. Automatic issuance ignores the product's own
  base rate table in favour of one global reference rate.
- **Bypass types, the case-to-policy back-reference, `NOT_TAKEN_UP`, s.119 free-look dates,
  re-opening a declined case.** All carried forward from Stage 1's out-of-scope list.

## Testing

- Integration: a case opened with a term outside the product's bounds is refused; one inside is
  accepted; a case with no term is accepted (the bounds are optional and so is the term).
- Integration: beneficiary shares that do not total 100 are refused at `openCase`.
- Integration: an accepted decision issues a policy carrying the case's term, premium-paying term,
  maturity date and beneficiaries.
- Integration: a quarterly-frequency case issues a quarterly premium, not a monthly one — the
  regression this change could most easily introduce.
- Frontend: the open-case schema's term/frequency/beneficiary rules, and the manual-issue prefill.
- E2E: a case opened with a term and a beneficiary issues automatically with both present.
