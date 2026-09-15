# Batch 1 — Product Validation & Measurement Model — Design

Date: 2026-09-15
Status: approved, not yet implemented

## Where this came from

A full read of the `product` module — every source file, all nine migrations,
the OpenAPI contract, the M13 and Build 3 specs, the console screens and every
consumer of `ProductApi` — produced a list of nine pieces of product
configuration that are authored but read by nothing, plus a set of defects that
are actively wrong rather than merely inert.

Six items were selected for work and decomposed into four batches, because a
validation guard and a cash-value accrual subsystem cannot share a design
document. This spec covers **Batch 1**: the small, true defects that live
entirely inside the product module and cost hours rather than days.

Batches 2 (the premium formula), 3 (product governance) and 4 (cash value
accrual) are out of scope here and are not designed by this document. Batch 4
remains blocked on an actuarial decision — which cash value basis the platform
should use — that is not an engineering choice.

## Evidence

The rules below were derived from the running dev database, not from reading
source alone. At the time of writing it holds 134 products and 128 product
versions, of which **9 carry a base rate table**. Three of those nine are
representative:

| Version | Declares it accepts | Actually prices |
|---|---|---|
| `779f3ac4` | entry ages 18–78 | FEMALE only 56–78; MALE only 18–56 |
| `c9453f49` | entry ages 18–40 | `NON_SMOKER` only, both sexes |
| `1fb8a33b` | no bounds declared | clean 2×2 rectangle, ages 18–30 |

`779f3ac4` is the case this batch exists for. It cannot price a single woman
under 56, or a man over 56, on a product that states it sells to 18–78. An
aggregate query over it looks healthy — six cells, six distinct (sex, smoker)
combinations, spanning 18 to 78 — and the defect is visible only per
combination. It is very likely the case behind the sentence already sitting in
`UnderwritingDecisionEventListener`'s javadoc: *"we never priced women under
56"*.

A second fact shaped the scope. Of 50 individuals in the party register, only
16 carry a recorded `sex` and `smoker_status`; **34 carry neither**, and the two
fields are perfectly correlated — no party has one without the other.

## Decisions taken

1. **Unrecorded smoker status maps to `UNKNOWN` at issuance**, so a product that
   deliberately priced the undeclared case gets used, and one that did not still
   refuses.
2. **A priced version must declare minimum and maximum entry age.** Without
   them there is no stated range to check a rate table against, and the table's
   own span silently becomes the product's selling range by accident — which is
   exactly how `779f3ac4` came to "accept" 18–78.
3. **`ifrs_measurement_model` moves from `product_definition` to
   `product_version`**, rather than being guarded where it sits.
4. **Missing sex is captured at client registration**, not solved by inventing a
   neutral sex value in the rate table.

## Why this shape

### The measurement model belongs on the version, and the evidence for it already does

`publishVersion` calls `activateWithMeasurementModel` unconditionally on every
publish, and the column lives on `product_definition`. So republishing a product
with a different model silently rewrites the measurement basis of **every
in-force contract ever issued under that product**, retroactively. There is no
test for it and nothing reads the field, which is why it has been invisible.

Guarding the field where it sits would stop the rewrite but leave an
incoherence: PAA eligibility turns on the coverage period, and
`min_term_months` / `max_term_months` already live on the **version**. The fact
that determines the model sits on the version while the model itself sits on the
product, and a product whose second version sells a thirty-year term instead of
a twelve-month one has no way to say its model changed except by silently
rewriting the first version's.

Moving the column puts the decision next to the evidence for it, and makes
in-force contracts immune by construction: a policy pins a `productVersionId`,
so it keeps its own basis forever. A legitimate change becomes what it should
have been all along — a new version.

It is also cheapest now. The field has zero readers outside the module,
`ProductSnapshotView` is already resolved per-version, and there are 128 rows to
backfill rather than 128,000.

### Coverage is checked per combination, because that is where the defect lives

Entry-age coverage alone would not have caught the real data. `c9453f49`'s bands
cover its declared 18–40 range for both sexes and would pass an age-only rule,
while pricing no smoker at all. `779f3ac4`'s bands span its full declared range
in aggregate and pass an age-only rule too, while leaving two enormous holes.

The rule therefore ranges over the cross-product of sex and smoker status, not
over age alone. A combination absent from the table covers nothing and fails,
which is what catches an asymmetric `FEMALE/SMOKER` row with no `MALE/SMOKER`
counterpart.

### `UNKNOWN` stays optional, and unreachable is not the same as unpriced

`SmokerStatus.UNKNOWN` is documented as *"a real, ratable value rather than a
null stand-in: a product may price undeclared smoker status deliberately"*. But
`UnderwritingDecisionEventListener` maps an unrecorded smoker status to `null`,
and `resolveBaseRatePerMille` returns empty for a null, so **the `UNKNOWN` cell
has never been reachable from the issuance path at all**. An actuary could
author it, see it on the product screen, and watch it price nothing — the same
shape as the AGE and SUM_ASSURED_BAND defects that V5 and V9 removed.

The two javadocs currently contradict each other on this point:
`resolveBaseRatePerMille` asserts *"null when unrecorded, which matches no cell
— a priced product needs the fact"*, which after this change is true of sex
only.

Requiring a declaration remains a legitimate underwriting stance, so a product
that does not price `UNKNOWN` is valid. It simply refuses those lives with a
message that says why, rather than with one that reads like a gap in the table.

### Sex has no neutral value, and that is deliberate

`Sex` is `FEMALE | MALE` by design — its javadoc is explicit that it exists only
because mortality differs measurably between the two, that it is a biological
rating input rather than an identity field, and that nothing on the platform
displays it. A third "unknown" value would not be a null stand-in; it would be a
unisex rate, which is a different actuarial object requiring its own table and
its own sign-off.

So an unrecorded sex keeps refusing. The fix is to capture the fact, which is a
party-module and registration-screen change and is **not in this batch**.

## Backend

### 1. Migration `V10__ifrs_measurement_model_on_version.sql`

```
ALTER TABLE product.product_version
    ADD COLUMN ifrs_measurement_model VARCHAR(10)
    CHECK (ifrs_measurement_model IN ('GMM','PAA'));

UPDATE product.product_version pv
   SET ifrs_measurement_model = pd.ifrs_measurement_model
  FROM product.product_definition pd
 WHERE pd.product_id = pv.product_id;

ALTER TABLE product.product_version
    ALTER COLUMN ifrs_measurement_model SET NOT NULL;

ALTER TABLE product.product_definition
    DROP COLUMN ifrs_measurement_model;
```

The backfill is verified safe against the current database: all 128 versions
join to a product carrying a non-null model, and no `DRAFT` product has a
version. A `DRAFT` product has a null model and no versions, because a version
only exists after a publish and every publish supplies one.

Written as a single migration rather than the two-phase expand/contract
sequence, because the column has no readers outside this module and there is no
rolling-deploy window to protect. If this platform ever adopts rolling deploys,
this is the migration to split.

### 2. `ProductVersion`

Gains `ifrsMeasurementModel`, set at construction and never mutated. The
constructor signature changes, which is a deliberate compile-time break so no
call site can create a version without one.

### 3. `ProductDefinition`

`activateWithMeasurementModel(String)` becomes `activate()`. The "measurement
model is required before a product may leave DRAFT" invariant stops being an
assertion in the aggregate and becomes structural: a version cannot exist
without a model, and only publishing a version activates a product.

`getIfrsMeasurementModel()` is removed along with the field.

### 4. `ProductApiImpl`

`getActiveSnapshot` and `getSnapshotByVersionId` read the model from the
resolved version rather than the definition. `ProductSnapshotView`'s shape is
unchanged — it is already a per-version view — so no consumer changes.

`publishVersion` passes the request's model into the `ProductVersion`
constructor and calls `product.activate()`.

### 5. `ProductApiImpl.rejectUncoveredEntryAges(List<BaseRateInput>, EligibilityBounds)`

New private static method, placed beside `rejectOverlappingAgeBands` and called
only on the priced branch of `publishVersion`.

**R1.** If `baseRates` is non-empty and either `minEntryAge` or `maxEntryAge` is
null, throw `InvalidProductVersionException`. The message states that a priced
version must say what ages it sells to, because the rate table's own span would
otherwise become that answer by accident.

**R2.** Let `S` be the distinct smoker statuses appearing in `baseRates`. For
every combination in `{FEMALE, MALE} × S`, the bands carrying that combination
must cover every age in `[minEntryAge, maxEntryAge]`.

Per combination: sort that combination's bands by `ageFrom`; set
`covered = minEntryAge - 1`; walk the sorted bands, and for any band whose
`ageFrom <= covered + 1`, set `covered = max(covered, ageTo)`; fail if
`covered < maxEntryAge` at the end. This tolerates overlapping bands and bands
extending beyond the declared range, and reports only holes that fall inside it.

The exception names the hole rather than the rule:

> Base rate table does not price FEMALE/NON_SMOKER for ages 18-55, but this
> version accepts entry ages 18-78.

Enforced in the application only, not in SQL. A constraint expressing this needs
`EXCLUDE ... USING gist` over an `int4range` and therefore `btree_gist` on every
environment — the same reasoning already recorded for `rejectOverlappingAgeBands`
and `rejectMalformedAgeBands`.

### 6. `UnderwritingDecisionEventListener.baseRatePerMilleFor`

The smoker status mapping changes from `null` to `SmokerStatus.UNKNOWN` when the
life assured has none recorded. The sex mapping is unchanged and still yields
`null`, which still refuses.

`resolveBaseRatePerMille` keeps its null guard, now reachable only through sex.

The refusal message gains its reason, so an underwriter reads why rather than
inferring it:

> Product version ... has no base rate for age 42, sex FEMALE, smoker status
> UNKNOWN. This client's smoker status was never recorded and this product does
> not price the undeclared case. Record the client's smoker status, or publish a
> version that prices it.

### 7. Javadoc corrections

`ProductApi.resolveBaseRatePerMille` — its `@param smokerStatus` note that
unrecorded "matches no cell" is now true of `sex` only, and must say so.

`SmokerStatus.UNKNOWN` — its claim that a product may deliberately price the
undeclared case becomes true in practice, and the note that "no party record
carries it, so a quote asserts it" needs the issuance path added.

## Frontend

`publishVersionSchema.ts` mirrors R1 and R2, as it already mirrors every other
publish rule. The console is the surface where an actuary can still fix the
table, and today it would accept `779f3ac4`'s shape without a word.

- When any base rate row carries a rate, `minEntryAge` and `maxEntryAge` become
  required, with a message that says why rather than "required".
- A coverage hole is reported against the offending `(sex, smoker)` column on
  the first row of that combination, naming the uncovered ages.

No other console change. Nothing displays the measurement model.

## Testing

`ProductApiIntegrationTest`:

- A published version carries its own measurement model.
- Republishing with a different model leaves the first version's model
  unchanged — the regression test for the defect this batch removes.
- A priced version with no entry-age bounds is refused.
- A priced version with a coverage hole is refused, and the message names the
  combination and the uncovered ages.
- A complete rectangle covering the declared range is accepted.
- An unpriced version still publishes with no bounds at all.

New unit test for the coverage walk, without a Spring context, following
`SimpleRulesEngineTest`'s precedent for pure logic: a gap in the middle,
overlapping bands, a band extending past the range, a single band covering
everything, and a combination absent entirely.

Policy side: issuance for a life with no recorded smoker status resolves
`UNKNOWN` and prices when the product carries that cell, and refuses with the
new message when it does not.

Frontend: `publishVersionSchema.test.ts` cases for R1 and R2.

`ProductVersion`'s constructor signature changes, so this branch needs
`clean test-compile` before `clean test`. Scoped runs will stay green and hide
the break in unchanged tests Maven never recompiles.

## Rollout

V10 must be applied by hand to the dev database and the backend restarted before
any real-stack check reflects it — green Testcontainers tests do not prove the
dev database is in sync.

The dev backend must be stopped before `clean test`, or `clean` deletes
`target/` under the live JVM and produces failures across untouched modules.

## Grandfathering

All validation runs at publish time, so the nine existing priced versions are
untouched and every in-force policy keeps its terms. **Eight of the nine would
now fail on republish** — the seven unbounded versions on R1, and `779f3ac4` on
R2 — which is the intent rather than a side effect. Only `c9453f49` would pass:
it declares 18–40 and its single priced smoker status covers that range for both
sexes. Correcting a published rating
table is a republish, not an `UPDATE`, which is the position `V8` already took
for the zero-multiplier row it declined to rewrite.

## Out of scope, and one dependency that matters

Capturing `sex` and `smoker_status` at client registration is **not in this
batch**. It is a party-module and registration-screen change, and it lands with
the queued staff client-registration work.

Until it does, **34 of 50 individuals cannot be issued a policy on any priced
product**, because an unrecorded sex refuses before smoker status is consulted.
This batch does not move that number, and nothing in it should be read as
having fixed the priced-issuance path for real clients.

## Open items

- **`c9453f49` prices only `NON_SMOKER`.** After this batch it stays publishable
  — both sexes are covered across its declared range, and `UNKNOWN` is
  optional. Whether a product may decline to price smokers at all is a product
  question, not a validation one, and is deliberately left alone here.
- **No maker-checker on publishing a rate table.** One `ROLE_ADMIN` call changes
  the price of a live product. That is Batch 3 and is not addressed here.
- **The nine priced versions are 7% of 128.** The other 119 still price from the
  single flat `TZ_BASE_PREMIUM_RATE_PER_MILLE` at issuance. Widening that is not
  an engineering task; it needs authored rates.
