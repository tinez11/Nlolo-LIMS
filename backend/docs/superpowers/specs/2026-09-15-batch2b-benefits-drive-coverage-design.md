# Batch 2b — Benefits Drive Coverage, and Coverage Values the Claim — Design

Date: 2026-09-15
Status: approved, not yet implemented

## Where this came from

The product-module audit found `benefit_schedule` authored and read by nothing:
issuance wrote one hardcoded `DEATH` coverage whatever a product declared, and
`calculation_method` was an unconstrained `VARCHAR(50)` holding, across **146
versions, just three rows** — carrying `SUM_ASSURED` and the typed prose
`untill death`.

(Counts throughout are as of 2026-09-15 against the dev database. The audit first
recorded 128 versions; e2e runs during batches 1–3 have since added more. The three
benefit rows have not moved.)

Batches 1, 2a and 3 are on `main`. This is Batch 2b.

## The finding that decides the scope

**`claimableCover` takes no claim type.**

```java
ClaimableCoverView claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf);
```

It returns `policy.getSumAssuredAmount()` and claims use that as the cover to
claim against for **every** claim type. `ClaimType` is
`DEATH, DISABILITY, CRITICAL_ILLNESS, MATURITY`.

So today **a critical-illness claim is valued at the full death sum assured**. In
a real insurer a CI rider pays a fraction — typically 25%, or a flat amount — and
paying a full death benefit for a survivable condition is a direct way to lose
money on claims nobody costed.

It also means `policy.coverage` is already decorative: one `DEATH` row per policy,
read only by `getCoverageStatus`, which is the endpoint M13 flagged for accepting
an `asOf` it ignores. Claims never touch it.

**Therefore wiring `benefit_schedule` to coverage creation is only worth doing if
it ends at the claim.** Steps 1–3 below are bookkeeping without step 4.

## Decisions taken

1. A three-value vocabulary: `SUM_ASSURED`, `PERCENTAGE_OF_SUM_ASSURED`,
   `FLAT_AMOUNT`.
2. `claimableCover` takes the benefit type and resolves the matching coverage. No
   matching coverage **refuses** the claim.
3. Publishing a version with **no benefits at all** becomes an error — but the 143
   existing versions that have none are **grandfathered**, and issuance keeps
   writing one `DEATH` coverage for them.

## Why this shape

### The vocabulary keeps the name that is already right

`SUM_ASSURED` is the only value in real use: 105 Java call sites, 12 JSON bodies,
and the one non-typo row in the database. Renaming it to something tidier would be
churn across 117 places for no reader benefit, so it stays.

`SUM_ASSURED_PLUS_BONUS` is **not** admitted. It appears in exactly one contract
test and nowhere in real data, and this platform has no bonus mechanism to compute
it — no reversionary bonus and no cash value, which is Batch 4 and still blocked on
an actuarial decision. Admitting a method nothing can calculate would recreate the
defect being removed: a value that looks authoritative and resolves to nothing.
That test changes to `SUM_ASSURED`.

`MATURITY` works under this vocabulary — an endowment maturing at its sum assured
is `SUM_ASSURED`, a real and computable answer. It cannot express with-profits
until a bonus concept exists, and that is stated rather than papered over.

### The migration normalises, where V5, V8 and V9 grandfathered

Those three migrations used `NOT VALID` because the columns they constrained drove
real money — a rating multiplier, a rate per mille — so rewriting history would
have altered priced contracts.

`calculation_method` is different in kind: **no computation has ever read it.** It
is displayed on one screen and nothing else. Normalising `untill death` to
`SUM_ASSURED` therefore changes no contract and no premium, and a fully validated
`CHECK` is achievable where V5 had to settle for `NOT VALID`.

The migration maps any value outside the three to `SUM_ASSURED`. With three rows in
the database this is a rounding error, and the alternative — failing the migration
on unknown data — would block deployment over a label that has never decided
anything.

### The claim type crosses the module boundary as a String

Claims may not reference `product.api.BenefitType`: `ClaimsApiImpl` records at line
144 that doing so fails `ModularityTests`, which is also why `ClaimableCoverView`
exists at all — "claims gets one number". `PolicyView.productCategory` is already a
`String` for precisely this reason.

So the new parameter is `String benefitType` and claims passes `claimType.name()`.
The four `ClaimType` values map one-to-one onto benefit types; `BenefitType.SURRENDER`
has no claim type and is simply never asked for.

### No matching coverage refuses the claim

The same reasoning as a rate table with a hole: paying a benefit nobody authored is
how a product pays for cover it never priced. A refusal is recoverable — the
product can author the benefit and the policy can be endorsed — while a payment is
not.

### Zero benefits: required going forward, grandfathered behind

**Only 3 of 146 versions have any benefit rows.** The console's publish form has
allowed an empty benefit schedule all along (`// no minimum coverage required`), and
143 versions took it.

So refusing to issue on a benefit-less version would make **143 of 146 products
unissuable**, including the seeded demo product and every product the e2e suite
uses. That is not a fix; it is an outage. And those versions cannot simply be
republished: under Batch 1 a republish now needs entry-age bounds and full rate-table
coverage, and under Batch 3 it needs a TIRA filing reference and approval date. That
is an actuarial and compliance exercise per product, and blocking all new business
until it is finished is a business decision, not an engineering one.

**The shape is therefore the one Batch 1 used for entry-age bounds: require it
going forward, grandfather what exists.**

- Publishing a version with an empty benefit schedule is refused. The console mirrors
  the rule, replacing its "no minimum coverage required" comment.
- A version that already has no benefit rows keeps today's behaviour exactly:
  issuance writes one `DEATH` coverage at the policy's sum assured.

**The property that makes this a migration path rather than a permanent default is
that the fallback set can only shrink.** Once zero-benefit versions cannot be
published, the population relying on the fallback is closed at migration time — 143
today, fewer as they are republished, never more. Had the console rule been left
alone, new versions could keep reaching the fallback forever, and it would be exactly
the kind of implicit default this audit has spent three batches removing.

It is also a different kind of fallback from the ones deleted. The flat
`TZ_BASE_PREMIUM_RATE_PER_MILLE` and the neutral `1.0` multiplier **invented** a
number standing in for product data nobody authored. This one restates a number the
contract already carries: `policy.sum_assured_amount` is the death cover, and it is
exactly what `claimableCover` returns today. Writing a `DEATH` coverage from it
asserts nothing new.

To keep it honest, the fallback is written as an explicit commented branch naming its
expiry condition — never an `orElse` that reads like a default — and a test asserts
the new publish rule, so the closure is provable rather than asserted.

## Backend

### 1. `BenefitCalculationMethod` — `product/api/BenefitCalculationMethod.java`

```java
public enum BenefitCalculationMethod { SUM_ASSURED, PERCENTAGE_OF_SUM_ASSURED, FLAT_AMOUNT }
```

### 2. `BenefitDefinition` — `product/api/BenefitDefinition.java`

```java
public record BenefitDefinition(BenefitType benefitType, BenefitCalculationMethod calculationMethod,
                                 BigDecimal percent, BigDecimal flatAmount) {
    BigDecimal amountFor(BigDecimal policySumAssured);
}
```

The compact constructor enforces the shape: `percent` present and `flatAmount` null
for `PERCENTAGE_OF_SUM_ASSURED`, the reverse for `FLAT_AMOUNT`, both null for
`SUM_ASSURED`, and a positive `percent` no greater than 100.

`amountFor` is the **one place** this arithmetic lives, exactly as
`FrequencyLoading.applyTo` is. Rounds to 2dp `HALF_UP` once, at the end.

### 3. Migration `V13__benefit_calculation_method.sql`

```
UPDATE product.benefit_schedule
   SET calculation_method = 'SUM_ASSURED'
 WHERE calculation_method NOT IN ('SUM_ASSURED','PERCENTAGE_OF_SUM_ASSURED','FLAT_AMOUNT');

ALTER TABLE product.benefit_schedule
    ADD COLUMN benefit_percent NUMERIC(5,2),
    ADD COLUMN flat_amount     NUMERIC(19,2);

ALTER TABLE product.benefit_schedule
    ADD CONSTRAINT benefit_schedule_calculation_method_known
        CHECK (calculation_method IN ('SUM_ASSURED','PERCENTAGE_OF_SUM_ASSURED','FLAT_AMOUNT')),
    ADD CONSTRAINT benefit_schedule_amount_shape CHECK (
        (calculation_method = 'SUM_ASSURED' AND benefit_percent IS NULL AND flat_amount IS NULL)
     OR (calculation_method = 'PERCENTAGE_OF_SUM_ASSURED' AND benefit_percent IS NOT NULL
            AND benefit_percent > 0 AND benefit_percent <= 100 AND flat_amount IS NULL)
     OR (calculation_method = 'FLAT_AMOUNT' AND flat_amount IS NOT NULL AND flat_amount > 0
            AND benefit_percent IS NULL));
```

Both constraints are **validated**, not `NOT VALID`, because the `UPDATE` above makes
every existing row conform. Added to all 46 migration lists.

### 4. `ProductApi`

`BenefitInput` widens to carry `percent` and `flatAmount`, and its
`calculationMethod` becomes `BenefitCalculationMethod` rather than a `String`.

**No `String` convenience constructor is kept.** Retaining one would leave the
free-text door open in the exact place this batch exists to close it, and a
stringly-typed overload is how `calculation_method` came to hold `untill death`. The
~105 existing `new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")` call
sites are updated instead — a safe targeted replace, because the pattern includes
`BenefitInput(` and cannot collide with unrelated string literals the way a bare
trailing argument can.

A two-argument constructor still exists for the common case, taking the enum and
defaulting both amounts to null: `BenefitInput(BenefitType, BenefitCalculationMethod)`.

New: `List<BenefitDefinition> resolveBenefitSchedule(UUID productVersionId)`,
internal-only, the same convention as `isPriced` and `resolveFrequencyLoading`.

`publishVersion` refuses an empty `benefitSchedule` with `InvalidProductVersionException`.

### 5. `PolicyApiImpl`

Issuance writes one `Coverage` per benefit at its computed amount. When the version
has **no** benefit rows, it writes a single `DEATH` coverage at the policy's sum
assured — the explicit grandfathering branch described above.

`claimableCover` gains `String benefitType`, resolves the coverage for it, and
throws `InvalidPolicyStateException` naming the benefit when none matches.

### 6. `ClaimsApiImpl`

Passes `claimType.name()` at both call sites.

## Frontend

The publish form's benefit rows gain a calculation-method select and a conditional
amount field — a percentage for `PERCENTAGE_OF_SUM_ASSURED`, an amount for
`FLAT_AMOUNT`, neither for `SUM_ASSURED`. `publishVersionSchema` mirrors the shape
rule and the new non-empty requirement, replacing its `// no minimum coverage
required` comment.

`RatingBasis` shows each benefit's method and amount rather than the raw string.

## Testing

- Unit tests for `amountFor` across the three methods and for the shape invariant,
  without Spring, beside `FrequencyLoadingTest`.
- `ProductApiIntegrationTest`: an empty benefit schedule is refused; a percentage
  benefit round-trips; the shape rule is enforced.
- `PolicyApiIntegrationTest`: issuance writes one coverage per benefit at the right
  amounts; **a version with no benefits still issues with a single DEATH coverage**,
  which is the grandfathering assertion and the one that proves nothing in force
  changed.
- `ClaimsApiIntegrationTest`: a CI claim against a death-only policy is refused; a CI
  claim against a product authoring CI at 25% is valued at 25% of the sum assured.
- Frontend schema tests for the method/amount shape and the non-empty rule.
- Full backend suite. `claimableCover` and `BenefitInput` both change shape, so the
  compile blast radius is real — and per Batch 3, **grep the test tree for
  hand-written JSON bodies too**, since a required field is invisible to the compiler
  wherever JSON is built by hand.

## Rollout

V13 applied by hand to the dev database with a backend restart, as V10–V12 were.

## What this does not do

- **It does not give `getCoverageStatus` a real `asOf`.** That endpoint still accepts a
  date and ignores it, because `policy.coverage` has no effective/expiry window. M13
  recorded this as the platform's single most consequential unanswered question in
  claims; it is untouched here and stays open.
- **It does not express with-profits.** `SUM_ASSURED_PLUS_BONUS` is deliberately absent
  until a bonus mechanism exists.
- **It does not endorse benefits onto in-force policies.** A grandfathered policy gets
  the coverage it has; adding a benefit to a live contract is an endorsement and is out
  of scope.

## Open items

- **143 versions rely on the grandfathering branch.** The count should only ever fall.
  Worth checking periodically: versions with zero benefit rows, against the total.
- **`SURRENDER` is a `BenefitType` with no `ClaimType`.** It is never asked for by
  `claimableCover`, which is correct today, but it means the two enums are not a clean
  bijection and a future surrender-as-claim flow would need a decision.
