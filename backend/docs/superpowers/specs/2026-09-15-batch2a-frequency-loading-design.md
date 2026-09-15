# Batch 2a — Frequency Loading, and One Price for One Life — Design

Date: 2026-09-15
Status: approved, not yet implemented

## Where this came from

The product-module audit produced six items in four batches. Batch 1 landed on `main` at
`16779b9`. This is **Batch 2a**, the first half of what was originally "the premium formula".

Batch 2 was split during brainstorming because two of its three items turned out to rest on a
vocabulary that does not exist. Wiring `benefit_schedule` to coverage creation needs
`calculation_method` to mean something machine-readable, and across 128 product versions the
database holds three rows carrying two values: `SUM_ASSURED`, and the typed prose `untill death`.
There is no `CHECK`, no enum, nothing — the same free-text void that produced the V5, V9 and
quote-path defects. That work is **Batch 2b** and is not designed here.

The policy fee was dropped from scope by decision, see below.

## Decisions taken

1. **Frequency loading is a loading percent per frequency**, stored on `product_version`, not a
   modal factor and not a rating-table factor type.
2. **There is no policy fee**, and it stays out of the premium calculation entirely.
3. **`quotePremium` resolves the sum-assured factor by range**, and its `sumAssuredBand` request
   field is removed rather than fixed.

## Why this shape

### Frequency loading is a payment term, not a risk fact

Today `PremiumFrequency` divides the annual premium by 12, 4 or 1 exactly, so a monthly payer and
an annual payer are charged the same total. Every real life insurer collects more from the monthly
payer, for four reasons that all apply to this platform specifically:

- **Investment income foregone.** The annual payer's whole premium is available to invest on day
  one; the monthly payer's is not.
- **Collection cost.** Twelve invoices, twelve payment attempts, twelve reconciliations, and
  twelve opportunities to enter the arrears and dunning ladder `billing` already runs.
- **Mid-year lapse.** A monthly policy that lapses in month seven carried seven months of risk
  against seven twelfths of the premium. Monthly business lapses more, and it lapses part-paid.
- **Commission already paid.** `distribution` accrues commission at issuance. On a policy that
  lapses part-paid the insurer has paid commission against premium it will never collect;
  clawback recovers part of it, and the loading prices the rest.

Market convention is monthly 5–9% above annual, quarterly 2–4%.

**Not a `rating_table` factor type**, which was the alternative and would have reused more
machinery — the authoring form, `getVersionRating`, the duplicate-band and positive-multiplier
rules all work unchanged there. It is rejected because `rating_table` is *risk* rating: it feeds
`RiskProfile` and therefore an underwriting decision. A frequency is not a risk fact, so every
consumer of that table would have to remember to exclude it and the publish-time coverage rule
would need an exception. One table meaning two things is precisely the ambiguity that produced the
band-string defects. Dedicated columns apply at one point and cannot leak into a decision.

**A loading percent, not a modal factor.** Actuarial tables store the modal factor (`0.0875` for
monthly), which encodes the uplift and the division in one number. It is more compact and can
express more. It is rejected because it cannot be shown to a policyholder as a reason: `0.0875`
does not announce that it is a 5% uplift, and `PremiumQuoteView` exists precisely so an
illustration can be explained. `PRODUCT.md` commits this console to rendering only what the
platform can prove.

### No policy fee, and that is a decision rather than an omission

The M13 spec left this open: *"Policy fee. Step 6 adds one, and no product field holds it. Either
it becomes a product-version field or it is out of the calculation; it should not be a constant in
code."*

The second branch is taken. No product field holds a policy fee, no reference-data key holds one,
and the premium calculation does not include one. The existing note on `PremiumQuoteView` — that
no policy fee is included because no product field holds one — stays true and stops being an open
item.

### The quote path still carries V9's defect

`quotePremium` resolves the sum-assured factor by matching a caller-asserted band string:

```java
strictMultiplier(versionId, FactorType.SUM_ASSURED_BAND, input.sumAssuredBand(), applied)
// -> .filter(f -> f.getBand().equals(band))
```

That is the exact mechanism V9 removed from the issuance path, where a product author's band
`'5000000'` could never match the three strings underwriting produced. Issuance now resolves by
range through `resolveSumAssuredMultiplier`; the quote does not. So the illustration and the policy
it becomes are priced by two different mechanisms, and the illustration uses the broken one.

It survived because M13 deliberately left the endpoint with no frontend caller, recorded there as a
considered decision rather than an oversight.

**The field is removed, not corrected.** A band string invented by a caller cannot be validated
against anything, and the sum assured — which the quote already carries — determines the band by
definition. Asking for both invites them to disagree. Safe to remove because nothing in the console
calls this endpoint.

### Two kinds of divergence, and only one is a defect

Removing the parameter makes the sum-assured factor neutral-on-no-match on the quote path, where it
was strict. That is the right direction: it matches issuance, and a sum assured above every band is
already treated platform-wide as a **soft** flag rather than a refusal, because above-retention
business is what the reinsurance treaties exist to absorb.

`occupationClass` stays asserted and strict on the quote, and neutral on issuance. **This
divergence is deliberate and is not closed here.** The distinction is what the two paths produce:

- Sum assured, before this change: both paths produced a price, and the prices disagreed. That is a
  defect.
- Occupation class: the quote refuses to guess a multiplier for a class the caller typed; issuance
  refuses to reject a case an underwriter has already decided, over a class the party record may
  legitimately lack. Those are two different answers to two different questions, and making either
  behave like the other would be worse.

## Backend

### 1. Migration `V11__frequency_loading.sql`

```
ALTER TABLE product.product_version
    ADD COLUMN monthly_loading_percent   NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN quarterly_loading_percent NUMERIC(5,2) NOT NULL DEFAULT 0;

ALTER TABLE product.product_version
    ADD CONSTRAINT product_version_frequency_loading_sane
        CHECK (monthly_loading_percent   >= 0 AND monthly_loading_percent   <= 100
           AND quarterly_loading_percent >= 0 AND quarterly_loading_percent <= 100);
```

No column for annual: it is the baseline the others load against, and a column for it could only
ever hold 0 or contradict itself.

`NOT NULL DEFAULT 0` rather than nullable, because "no loading" is a real pricing decision — charge
the same whatever the frequency — and not an absence. Every one of the 128 existing versions
therefore gets 0 and prices exactly as it does today: **this migration changes no premium
anywhere.**

Added to the migration list of all **46** test classes that load the product schema, for the reason
V10 established: the list is explicit per class, `ddl-auto` is `none`, and a class missing it fails
at its first product-version query rather than at boot.

### 2. `ProductVersion`

Gains `monthlyLoadingPercent` and `quarterlyLoadingPercent` as `BigDecimal`, both defaulting to
`BigDecimal.ZERO` in the field initialiser so a version constructed without them is unloaded rather
than null.

**A `FrequencyLoading(BigDecimal monthly, BigDecimal quarterly)` record carries them across the
API**, with a `none()` factory mirroring `EligibilityBounds.none()` and a compact constructor that
normalises null to zero and rejects anything outside 0–100.

The record is not there for an invariant — two independent percentages have none worth centralising
— it is there to stop `publishVersion`'s fullest overload reaching **twelve parameters**. This
codebase has already made that call once and written down why, on `IndividualRegistration`:

> A parameter object rather than a widened parameter list. `registerIndividual` had five flat
> parameters and roughly fifty call sites; growing that signature to seventeen would have been a
> large merge surface for no reader benefit.

The same reasoning applies with more force here, since `publishVersion` already takes ten.
`PublishVersionRequest` gains one optional nested object, exactly as it does for `eligibility`.

### 3. `ProductApi`

`PremiumQuoteInput` loses `sumAssuredBand`. `PremiumQuoteView` gains two fields so the derivation
stays complete:

- `frequencyLoadingPercent` — what was applied, 0 when none
- `annualAfterFrequencyLoading` — the figure the instalment is actually divided from

Both are required. Without them the view reports an annual figure that does not divide into the
instalment beside it, which is worse than not showing the loading at all.

`publishVersion`'s fullest overload takes one `FrequencyLoading`. The two convenience overloads pass
`FrequencyLoading.none()`, so every existing call site is unchanged.

### 4. The formula, in both paths

```
annual     = sumAssured × ratePerMille/1000 × ratingMultipliers × (1 + underwritingLoading/100)
loaded     = annual × (1 + frequencyLoading/100)
instalment = loaded / instalmentsPerYear          -- rounded ONCE, HALF_UP, 2dp
```

The frequency loading is applied **last**, after the risk arithmetic and the underwriting loading.
Multiplication commutes and rounding still happens once at the end, so its position does not change
the number — it changes whether a reader can follow the breakdown. A payment term sitting outside
the risk terms is the honest shape.

`ANNUALLY` resolves to 0 with no column read.

Both `ProductApiImpl.quotePremium` and `UnderwritingDecisionEventListener` apply it. That is the
point of the change: the illustration and the first invoice come from one formula.

### 5. `ProductController` and the wire

`PremiumQuoteRequest` loses its `@NotNull String sumAssuredBand`, and `openapi-product.yaml` loses
the property from the request schema and gains the two new response properties. `PublishVersionRequest`
gains two optional percentages, defaulting to zero when absent.

## Frontend

Two numeric fields in the publish form beside the eligibility bounds, using the same
optional-numeric-kept-as-string handling every other numeric there uses — blank means 0, never a
coerced `NaN`, for the reason the schema file already records about `z.coerce.number()`.

`publishVersionSchema` validates 0–100 and mirrors the `CHECK`. `RatingBasis` shows the loadings
when either is non-zero, and says nothing when both are zero rather than printing "0%" twice.

No console change for the quote: it still has no caller.

## Testing

- Unit test of the loading arithmetic without Spring, beside `ProductCoverageRuleTest`: 0 loading
  leaves the instalment unchanged; 8% monthly on a 12,000 annual gives 1,080.00; quarterly and
  annual; rounding lands once at the end.
- `ProductApiIntegrationTest`: a loaded version quotes the loaded instalment and returns both new
  view fields; a 0-loading version quotes exactly what it does today.
- `PolicyApiIntegrationTest`: **the same product, the same life, the same frequency — quote and
  issuance produce the same instalment.** This is the test the batch exists for, and it is the one
  that would have caught the sum-assured divergence.
- A test that a quote no longer accepts `sumAssuredBand` and resolves the multiplier from the
  amount, including an amount covered by no band resolving neutral rather than refusing.
- Frontend schema tests for the 0–100 bounds and the blank-means-zero handling.
- The e2e publish journey fills both fields.

Full backend suite, not a scoped run: V11 touches all 46 migration lists. `clean test-compile`
first, because `PremiumQuoteInput` and `PremiumQuoteView` change shape and Maven's incremental
compile hides exactly that.

## Rollout

V11 applied by hand to the dev database with a backend restart, as V10 was — green Testcontainers
runs do not prove the dev database is in sync.

## Out of scope

- **Batch 2b**, benefits driving coverage creation, which needs a `calculation_method` vocabulary
  and a sum-assured basis per benefit row.
- **Batch 3**, maker-checker and TIRA filing fields on publish.
- **Batch 4**, cash value accrual, still blocked on an actuarial decision about the basis.
- The `occupationClass` strict-versus-neutral divergence, deliberately, for the reason given above.
