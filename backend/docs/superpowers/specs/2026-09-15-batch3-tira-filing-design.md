# Batch 3 — TIRA Filing on a Product Version — Design

Date: 2026-09-15
Status: approved, not yet implemented

## Where this came from

The product-module audit found that publishing a product version — the single most
financially consequential action on this platform — is one `ROLE_ADMIN` POST with no
second pair of eyes, no filing reference, no approval date, and no evidence that the
regulator ever saw the rates. In Tanzania, products and their rates must be filed with
and approved by TIRA before sale.

Batches 1 and 2a are on `main`. This is Batch 3, and it deliberately implements **half**
of what the audit asked for.

## The decision, and what it leaves open

Two shapes were considered:

1. **Full maker-checker.** A `PRODUCT_APPROVER` who is not the publisher approves a
   version before it becomes usable, supplying the filing reference.
2. **Filing record only.** Publishing stays a single call but must carry a TIRA filing
   reference and approval date.

**Shape 2 was chosen.** Shape 1 was recommended and declined, and the reason it was
recommended is recorded here rather than dropped: after Shape 1, no single person could
change a live product's price. After Shape 2, one ADMIN still can — now accompanied by a
reference they typed themselves.

**So the maker-checker finding stays OPEN.** This batch makes the filing auditable. It does
not introduce separation of duties, and nothing in it should be read as having done so. See
"What this does not do".

A practical fact that shaped the choice: `staff.admin` is the **only** account with the
ADMIN role, and it also holds `FINANCE_OFFICER`, `UNDERWRITER`, `CLAIMS_ASSESSOR`,
`CLAIMS_MANAGER` and `CUSTOMER_SERVICE_REP`. Any maker-checker needs a second seeded
account before it is usable at all — in dev, and in the e2e suite, which publishes products
constantly.

## Decisions taken

1. The filing reference and approval date are **mandatory** on publish, not optional.
2. The 133 existing versions are **grandfathered** with nulls.
3. An approval date in the future is refused. An approval date after the version's
   effective date is **not** refused — see below.
4. The filing is read back on `VersionRatingView`, not on `ProductSnapshotView`.

## Why this shape

### Mandatory, because an optional compliance field is one nobody fills in

Optional-on-the-API-but-required-in-the-console was considered and rejected. It costs
almost nothing — the ~103 existing publish call sites would be untouched — but it leaves
the catalogue holding versions with no filing evidence and nothing to distinguish them from
versions whose filing was genuinely recorded. The whole point of this batch is the record,
so the record is required.

There is deliberately **no convenience overload** that defaults the filing to absent. That
would be a hole exactly where the requirement lives, and it is how "mandatory" quietly
becomes "optional in practice".

### Grandfathered with `NOT VALID`, the pattern this platform already uses

No filing reference exists for any of the 133 versions currently in the database, and
inventing one would be worse than a null — it would be a fabricated compliance record.

```
CHECK (tira_filing_reference IS NOT NULL AND tira_approval_date IS NOT NULL) NOT VALID
```

`NOT VALID` enforces this on every insert and update from here on while declining to
re-litigate history. That is precisely what `V5__rating_table_age_bounds`,
`V8__rating_table_multiplier_positive` and `V9__rating_table_sum_assured_bounds` each did,
and each recorded why. The application check in `publishVersion` runs first, so the failure
names the missing field instead of surfacing as a raw constraint violation — the same
arrangement as every other publish rule.

### A future approval date is refused; an approval AFTER the effective date is not

A filing approved tomorrow does not exist, so that is refused outright.

The other ordering rule — that a version must not be effective before the date its filing
was approved — is a **real** compliance rule, and it is deliberately not enforced. Two
reasons:

- **Backdated effective dates are legitimate here.** This platform corrects a published
  version by republishing it (V8 established that a published rating table is a priced
  contract term, so correcting one is a republish and never an `UPDATE`). A correction
  naturally carries the original's effective date while its filing is dated later.
- **There is no override.** Making it a hard refusal on a rule with no exception path is how
  staff end up typing a date that gets them past the gate, which produces a worse record
  than no rule at all.

Recorded as an open item rather than passed over in silence.

### The filing reads back on the version's rating view

`VersionRatingView` is the staff-only, version-level read an actuary reviews a version on,
and it is where the frequency loading landed in Batch 2a. The filing belongs with it.

Deliberately **not** on `ProductSnapshotView`: that view is consumed by billing, policy and
underwriting on the issuance and billing path, and M13 already established that it does not
get fattened with data exactly one screen needs.

## Backend

### 1. `TiraFiling` record — `product/api/TiraFiling.java`

```java
public record TiraFiling(String reference, LocalDate approvalDate) { }
```

A compact constructor trims the reference and refuses a blank one, a null approval date, and
an approval date after today. A parameter object rather than two more parameters, for the
reason `FrequencyLoading` is one and `IndividualRegistration` records at length:
`publishVersion`'s fullest overload is already at eleven parameters.

Validation lives in the record rather than in `ProductApiImpl` so that no caller can
construct an invalid filing to pass anywhere — the same arrangement as
`EligibilityBounds`'s ordering invariant and `FrequencyLoading`'s 0–100 bounds.

"Today" is evaluated against the system clock at construction. A filing dated today is
valid; one dated tomorrow is not.

### 2. Migration `V12__tira_filing.sql`

```
ALTER TABLE product.product_version
    ADD COLUMN tira_filing_reference VARCHAR(60),
    ADD COLUMN tira_approval_date    DATE;

ALTER TABLE product.product_version
    ADD CONSTRAINT product_version_tira_filing_recorded
        CHECK (tira_filing_reference IS NOT NULL AND tira_approval_date IS NOT NULL)
        NOT VALID;
```

`VARCHAR(60)` rather than a tighter guess: the platform has been bitten twice by a column
too short for a value the system already produces (M7's `VARCHAR(15)` against a 16-character
event name, M9's `VARCHAR(30)` against a 31-character one), and no TIRA reference format is
recorded anywhere in this repo.

Added to the migration list of all **46** test classes that load the product schema, for the
reason V10 established: the list is explicit per class, `ddl-auto` is `none`, and a class
missing it fails at its first product-version query rather than at boot.

### 3. `ProductVersion`

Gains `tiraFilingReference` and `tiraApprovalDate`, with `applyTiraFiling(TiraFiling)` and
`getTiraFiling()` mirroring `applyEligibilityBounds` / `applyFrequencyLoading`. Returns null
from `getTiraFiling()` when the columns are null, which is the honest representation of a
grandfathered version — not `TiraFiling.none()`, because there is no such thing as a filing
that is present and empty.

### 4. `ProductApi` and `ProductApiImpl`

`publishVersion`'s fullest overload takes a `TiraFiling` after `frequencyLoading`. **The two
convenience overloads are removed from the "defaults it" business**: they now also require a
`TiraFiling`, because a default would reintroduce the hole. This is the change that reaches
the 103 call sites.

`publishVersion` refuses a null filing with `InvalidProductVersionException` naming the
field, before any other publish validation, so the message is about the filing rather than
about a rating table the caller may not have reached yet.

`VersionRatingView` gains `TiraFiling tiraFiling`, null for a grandfathered version.

### 5. Wire

`PublishVersionRequest` gains a required `@NotNull @Valid TiraFilingRequest tiraFiling`.
`openapi-product.yaml` adds it to `ProductVersionSpec`'s `required` list and properties, and
adds the read shape to `VersionRatingView`.

## Frontend

Two required fields on the publish form — a text reference and a date — mirroring the server
rules including the future-date refusal, using the same blank-stays-blank string handling
every other field there uses.

`RatingBasis` shows the filing when present, and says so plainly when absent rather than
rendering an empty row: a version published before V12 genuinely has no filing on record,
which is a different fact from a blank one.

## Testing

- Unit test of `TiraFiling` without Spring, beside `FrequencyLoadingTest`: a blank reference,
  a null date, a future date, a date of today, and trimming.
- `ProductApiIntegrationTest`: a publish with no filing is refused naming the field, and a
  valid filing round-trips onto `getVersionRating`.
- **A grandfathered version reads back null**, and this one cannot be built through the API —
  once the rule is in, no publish can produce an unfiled version. The test publishes normally
  and then nulls the two columns through `ProductVersionRepository`, which is the only way
  that state now occurs and is exactly how the 133 existing rows look. Same technique Batch 1
  used to reach a rate table with a sex hole after the coverage rule made it unpublishable.
- `ProductContractTest`: the filing survives a publish over HTTP and is readable back — the
  seam a service-level test cannot see, and the one that caught `frequencyLoading` missing
  from `ProductVersionSpec` in Batch 2a with every backend test green.
- Frontend schema tests for both fields and the future-date rule.
- The e2e publish journey fills both and asserts they render.

Full backend suite, not a scoped run: V12 touches all 46 migration lists and the
`publishVersion` signature changes. `clean test-compile` first — Batch 2a proved this exact
trap surfaces as a Testcontainers error pointing at Docker.

## Rollout

V12 applied by hand to the dev database with a backend restart, as V10 and V11 were.

## What this does not do

**One ADMIN can still change a live product's price in a single call.** This batch adds a
filing reference to that call; it does not add a second approver, and `staff.admin` remains
the only account able to publish at all. The maker-checker finding from the product audit
stays open and should be read as open.

**Nothing verifies the reference.** There is no TIRA integration, no format validation
beyond non-blank, and no check that the reference corresponds to a real filing. The field
records what a human asserts.

## Open items

- **Approval date versus effective date.** A version effective before its filing was
  approved is a compliance failure this batch makes visible but does not prevent, for the
  reasons given above. Worth revisiting with an override path rather than a bare refusal.
- **No filing document.** The platform has a `document` module and KYC already attaches
  evidence; a filing certificate is the obvious thing to attach and is not in scope here.
- **Grandfathered versions are indistinguishable from unfiled ones going forward** only in
  the sense that both read null. In practice nothing published after V12 can be null, so a
  null means "published before 2026-09-15" — but that is an inference from the migration
  date, not a recorded fact.
