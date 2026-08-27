# M13 — Premium Pricing & Read Gaps — Design

**Goal:** Close the four cheapest gaps between what the platform's own design documents promise and what it serves, and start the one long-lead item that everything downstream waits on. Specifically: implement the base rate tables Deliverable 1 §2.1 already specifies, publish a premium calculation from Product Configuration, expose the rating basis for actuarial review, add the missing `GET /agents` list, and put a controller on the audit event log.

This milestone deliberately adds **no new bounded context**. Every item is an aggregate or endpoint the existing design already calls for.

## Where this came from

An operations-console mockup (`nloloopsconsole.html`) was reviewed against the real API surface: 34 screens against 79 operations. 12 screens are fully servable today and all 12 are already built; 5 render but are thin; 17 have no backend at all. Of those 17, twelve belong to four bounded contexts that appear nowhere in Deliverable 1 — Lead & Advisory, Quotation, Proposal, Customer Service & Complaints. The mockup cites "Addendum A §2.18/§2.20/§2.21" as their design authority; **no such document exists in this repository**, and grepping all eleven deliverable docs returns zero hits for Lead, Quotation, Proposal, Complaint or Customer Service. Those four contexts are undesigned, and designing them is explicitly out of scope here.

What is in scope is the residue: capabilities the seventeen existing contexts were designed to have and do not.

## Why this shape

### Pricing is an implementation shortfall, not a design gap

Deliverable 1 §2.1 gives Product Configuration responsibility for "benefit structures, **premium rating factors, mortality/morbidity tables**, surrender/loan rules" and names `RatingTable` a core aggregate. What shipped is `product.rating_table` holding `(factor_type, band, multiplier)` — the *factors*, with no base to multiply. `FactorType` covers `AGE | OCCUPATION_CLASS | SMOKER_STATUS | SUM_ASSURED_BAND`; a set of multipliers cannot produce a premium on its own.

So this is not a new idea. It is the missing half of an aggregate that was specified from the start.

### `BaseRateTable`, and `MortalityTable` stays unclaimed

A true mortality table (`qx` by age and sex) only becomes a premium once an interest assumption, an expense loading and a profit margin are also decided — and those touch IFRS 17 measurement, which is still Deliverable 1 open question #4, awaiting actuarial input. Building that machinery now would mean guessing three assumptions in order to deliver one screen.

Instead: **`BaseRateTable`** — a rate per mille of sum assured per year, keyed by `(age band × sex × smoker status)`, authored per product version by the actuary. The existing `rating_table` multipliers apply on top, unchanged, so occupation class and sum-assured band keep pricing independently.

It is named for what it is. Calling a rate table a mortality table would be exactly the silent rename the no-silent-rename rule exists to prevent, and would imply a derivation the platform does not perform. `MortalityTable` is left unclaimed for whoever later builds the real thing.

### The neutral-multiplier fallback must not reach the pricing path

`ProductApiImpl.resolveRatingMultiplier` returns `BigDecimal.ONE` when no band matches, with the comment "neutral multiplier, not an error". `UnderwritingApiImpl` (lines 111–112) already consumes it for `AGE` and `SUM_ASSURED_BAND`, and documents the fallback at line 131.

For risk scoring that is defensible: an unmatched band means "no adjustment known". **For a premium it is a silent mispricing** — a mistyped or missing age band would quietly price at the base rate and nothing would fail. The pricing path therefore resolves strictly: a band that does not match is a `422`, naming the factor and the value. `resolveRatingMultiplier` keeps its current behaviour for underwriting; pricing does not reuse it.

This is the same class of defect as a control account that accepts manual journals — a fallback that is correct in one context and dangerous in another, reachable from both.

### Product Configuration owns the calculation

§2.1 establishes Product as an Open Host Service whose consumers "consume its published `ProductSnapshot` rather than reading live config". Pricing has the same shape, so it is published the same way: `POST /products/{productId}/premium-quote`.

The alternative — a future Quotation context doing the arithmetic over Product's tables — was rejected. Billing also needs a premium when it builds a schedule; two implementations means two answers to one question. And it would put a sales artefact with its own lifecycle inside the context that owns rating.

### The quote endpoint persists nothing

`premium-quote` is a calculation. It stores no row, publishes no event, and takes **no `Idempotency-Key`** — there is no duplicate to prevent, and replaying it is free.

`POST` rather than `GET` because the request carries `dateOfBirth`, `sex` and `smokerStatus`. A `GET` puts those in a URL, and therefore into access logs, proxy logs and browser history. That is a data-protection problem, not a style preference.

**Consequence for the frontend:** the Axios interceptor that asserts `Idempotency-Key` presence on the six hard-required endpoints must *not* gain a seventh entry for this path, or a live-pricing form throws on every keystroke.

### Entry age is derived server-side

The request takes `dateOfBirth` and `asOf`, never a precomputed `age`. Age at entry is the input a mispriced policy turns on, and the mockup computes it as `2026 - dob.slice(0,4)` — wrong by up to a year for anyone whose birthday has not passed. The band lookup happens where the rate table lives.

### The rating basis is readable, but not through `ProductSnapshot`

`ProductSnapshotView` is consumed by Underwriting and Billing on the issuance and billing path. Fattening it with rate tables would make every consumer carry data exactly one screen needs, and it cannot be role-scoped because Billing must keep reading it.

So the rating basis gets its own endpoint, scoped to product/actuarial roles: `GET /products/{productId}/versions/{versionId}/rating`.

### Riders are out

The mockup prices seven riders as percentage loadings. `BenefitScheduleEntry` already exists with `BenefitType: DEATH | DISABILITY | CRITICAL_ILLNESS | MATURITY | SURRENDER`, and four of the seven riders (accidental death, critical illness, TPD, hospital cash) overlap that enum directly.

Shipping riders as a parallel percentage list would create a second vocabulary for benefits `BenefitSchedule` already names, and whichever the UI sent first would become the de facto schema — the same failure already documented for `EndorsementRequest.changes` and M7's commission semantics. `premium-quote` takes no riders parameter, not even a reserved one.

### The audit log is an event journal, not a who-did-what trail — corrected scope

This item was initially sized as "a controller on an existing API". That was wrong, and the correction matters.

`audit.audit_log` records `(event_id, event_type, schema_version, sequence_number, occurred_at, recorded_at, payload)`. `event_type` is a domain event name (`'policy.PolicyIssued'`), the payload is the event's JSON, and the writer is `DomainEventAuditListener`. **There is no actor column.** `AuditApi` exposes exactly one method, `getTrail(EntityRef, DateRange)`, returning a per-entity list — not a tenant-wide feed. `AuditEntryView` carries `(eventId, eventType, occurredAt, payloadJson)`.

The mockup's compliance screen shows actor · category · action · record · before · after · reason. **Five of those seven columns do not exist anywhere in the platform.** Producing them means either adding actor and before/after to every domain event payload, or building a separate actor-audit mechanism alongside the event journal — neither is a controller.

What is in scope here is therefore narrower and honestly labelled: **a paged, tenant-scoped, event-type-filterable read over the event journal**. It answers "what happened to this tenant, in order" and it does not answer "who did it and why". The screen built on it must say so rather than presenting an event feed as a compliance trail.

Two operational notes carried from the schema: the table is `PARTITION BY RANGE (occurred_at)` with monthly partitions declared only through 2026-09, so partition creation is an ongoing operational task; and retention is flagged in the schema comments as an open compliance decision, unresolved.

### `GET /agents` closes an asymmetry, not a design gap

`distribution` serves `POST /agents`, `GET /agents/me`, `GET /agents/{agentId}` and everything per-agent — commission plan, statements, accruals, payout, suspend, reactivate — but no list. Any agent table has no feed. `GET /parties` already established the shape for a paged, tenant-scoped, `q`-searchable staff list; this mirrors it exactly rather than inventing a second convention.

## Backend

### 1. `BaseRateTable` — new aggregate in `product`

**Migration** `backend/db-migrations/product/V2__base_rate_table.sql`, following the conventions in `V1__create_product_schema.sql` (tenant column, RLS policy, app-role grants):

```sql
CREATE TABLE product.base_rate_table (
    base_rate_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    age_band            VARCHAR(20) NOT NULL,   -- same band vocabulary as rating_table
    sex                 VARCHAR(10) NOT NULL,   -- FEMALE | MALE
    smoker_status       VARCHAR(12) NOT NULL,   -- SMOKER | NON_SMOKER | UNKNOWN
    rate_per_mille      NUMERIC(10,4) NOT NULL CHECK (rate_per_mille > 0),
    UNIQUE (product_version_id, age_band, sex, smoker_status)
);
CREATE INDEX idx_base_rate_version ON product.base_rate_table (product_version_id, age_band);
ALTER TABLE product.base_rate_table ENABLE ROW LEVEL SECURITY;
-- + tenant_isolation policy and grants, matching rating_table in V1
```

`rate_per_mille > 0` as a table constraint, not only a bean validation: a zero or negative rate is a free or paying-the-customer policy, and that must be unrepresentable at rest.

**Domain** `product/domain/BaseRate.java`, mirroring `RatingFactor`. **Repository** `product/infrastructure/BaseRateRepository.java`.

**Authoring** extends the existing publish path rather than adding a second one. `PublishVersionRequest` gains `baseRates: List<BaseRateRequest>`; `ProductApi.publishVersion` gains a `List<BaseRateInput>` parameter; `ProductApiImpl.publishVersion` persists them in the same transaction as the rating factors.

Validation at publish, alongside the existing rating-factor coverage check that already throws `InvalidProductVersionException`: **a version whose category requires pricing cannot be published with an empty base rate table**. A product version that can be sold but not priced is the state this milestone exists to eliminate; it should not be creatable.

### 2. `POST /products/{productId}/premium-quote`

```java
// ProductApi
record PremiumQuoteInput(
    UUID productId, Money sumAssured, LocalDate dateOfBirth, Sex sex,
    SmokerStatus smokerStatus, String occupationClass,
    PremiumFrequency frequency, Integer termYears, LocalDate asOf) {}

record AppliedFactor(FactorType factorType, String band, BigDecimal multiplier) {}

record PremiumQuoteView(
    UUID productVersionId, Money amount, PremiumFrequency frequency,
    String ageBand, BigDecimal ratePerMille, Money annualBase,
    List<AppliedFactor> appliedFactors, Money annualAfterFactors,
    Money policyFee, Money total) {}

PremiumQuoteView quotePremium(PremiumQuoteInput input);
```

The derivation is returned, not just the total, for two reasons. "Reproduce exactly what the customer was shown on a given date" is a stated requirement, and without the breakdown the only way for a UI to show one is to recompute it — which is the client-side arithmetic `frontend/PLAN.md` §4 forbids and the mechanism by which an illustration and its first invoice come to disagree.

Order of operations, fixed and documented on the interface:

1. Resolve the product version active as of `asOf` — reusing `productVersionRepository.findActiveAsOf`, the same lookup `getActiveSnapshot` already uses.
2. Derive entry age from `dateOfBirth` and `asOf`. Map to `age_band`.
3. Look up `rate_per_mille` for `(age_band, sex, smokerStatus)`. **No match is a `422`, not a fallback.**
4. `annualBase = sumAssured / 1000 × ratePerMille`.
5. Apply `OCCUPATION_CLASS` and `SUM_ASSURED_BAND` multipliers from `rating_table`, strictly — no match is a `422` naming the factor and value.
6. Add the policy fee for the frequency; divide by the frequency divisor.
7. Money arithmetic in `BigDecimal` with `HALF_UP` at 2 decimal places, matching the platform's existing `Money` handling. Rounding happens once, at the end.

`AGE` and `SMOKER_STATUS` factors in `rating_table` are **not** applied — those dimensions are already keys of the base rate table, and applying them twice would double-count. Enforced by validation at publish: a version may not carry `rating_table` rows for `AGE` or `SMOKER_STATUS` once it carries base rates. This is the single most likely defect in the whole milestone and it is cheapest to make unrepresentable.

**Controller** `POST /products/{productId}/premium-quote`, `@PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")`. No `Idempotency-Key`. Returns `200`, not `201` — nothing was created.

### 3. `GET /products/{productId}/versions/{versionId}/rating`

Returns base rates, rating factors and benefit schedule for one version. `@PreAuthorize` restricted to the product/actuarial staff role — narrower than `ProductSnapshot`, which Billing and Underwriting must keep reading. Read-only; authoring stays on the publish path.

### 4. `GET /agents`

```java
// AgentApi
Page<AgentView> listAgents(String q, AgentStatus status, Pageable pageable);
```

Repository derived queries plus a JPQL `search()` for the `q` case, mirroring `PolicyController`'s branch structure. Tenant-scoped via `TenantContext.get()`. `q` is a case-insensitive substring match against the agent's display name, matching `GET /parties`' documented behaviour. Staff-only: an agents-realm caller listing every agent in the tenant is a new disclosure, and agent-of-record scoping does not exist on this entity — the same reasoning that kept `GET /underwriting/cases` staff-only.

### 5. Audit read

```java
// AuditApi -- new method alongside the existing getTrail
Page<AuditEntryView> listEvents(String eventTypePrefix, DateRange range, Pageable pageable);
```

`GET /audit-log`, staff-only, paged, ordered `occurred_at DESC`, optional `eventTypePrefix` (so `policy.` filters to one module) and date range. `AuditEntryView` is reused unchanged — it exposes `payloadJson` raw, which is honest: the payload shape varies per event type and inventing a normalised view over 40-odd event types would be a translation layer nobody asked for.

A new spec file `backend/api/openapi/openapi-audit.yaml` — the 16th. **`frontend/scripts/generate-api-types.mjs` enumerates the specs explicitly and must gain the entry**, or the generated types silently omit the module; the deleted portal's script covered 11 of 15 for exactly this reason.

## Frontend

Deliberately thin. Phase 0 of the frontend restructure (a shared `FormField`, a screen manifest, a `gates` module) is proceeding independently and is not part of this milestone.

- `api/products.ts` + `productStore` gain `quotePremium` and `getVersionRating`.
- `api/distribution.ts` + `distributionStore` gain `listAgents`; a new `AgentsPage` using the existing paged `DataTable`, and a nav entry — the first list-backed nav item Distribution has had.
- A new `api/audit.ts` and `auditStore`; an `AuditLogPage` on the client-side table variant. Its page description must state that this is a domain-event journal, not an actor trail, so nobody reads it as compliance evidence it cannot provide.
- No pricing arithmetic anywhere. The derivation from `PremiumQuoteView` is rendered as strings.

## Testing

- **`BaseRateTable` publish validation:** empty base rates rejected for a priceable category; `AGE`/`SMOKER_STATUS` rating factors rejected alongside base rates; negative rate rejected by the DB constraint, not only the bean.
- **`quotePremium` arithmetic:** a table-driven test with rates and multipliers whose expected premium is computed by hand in the test, not by the implementation. At least one case per frequency, and one where rounding matters at the second decimal.
- **Strict resolution:** an unmatched age band, sex, smoker status, occupation class and sum-assured band each produce `422` — five separate assertions. This is the fallback hazard; it needs coverage per dimension, not one representative case.
- **Version pinning:** a quote with `asOf` before a version's effective date prices on the earlier version. Regression guard for the whole point of versioning.
- **Idempotency:** an integration test asserting `premium-quote` is *absent* from the hard-required `Idempotency-Key` set, so a later sweep does not add it by symmetry.
- **`GET /agents`:** tenant isolation (an agent of tenant B never appears), `q` matching, status filter, and a `403` for an agents-realm token.
- **Audit:** tenant isolation, ordering, `eventTypePrefix` filtering, and a query spanning two monthly partitions.
- **Contract tests** for all new paths, matching the existing per-module `*ContractTest` pattern.
- Every test runs against real Postgres via Testcontainers on the host with `./mvnw` — never Maven inside Docker, which breaks Testcontainers networking. No fabricated JWTs: tokens come from the real Keycloak container, per the standing rule that has twice caught an unusable credential path behind a green suite.

## Rollout

1. Migration + `BaseRate` domain/repository, no API surface. Deployable alone.
2. Publish-path validation and authoring. Existing versions have no base rates and therefore cannot be quoted — **the validation must apply at publish, not at read**, or every in-force product version becomes retroactively invalid.
3. `premium-quote` + rating read endpoint.
4. `GET /agents`, audit read, spec files, codegen entry.
5. Frontend wiring.

Nothing here changes an existing response shape, so no consumer breaks. `ProductSnapshotView` is untouched by design.

## Open items

- **The rates themselves.** This milestone builds the table and the arithmetic; it does not supply a single rate. Every rate per mille needs actuarial authorship, and until they exist no product version can be published under the new validation. **This is the gate on the whole milestone and it is not an engineering task.**
- **Age band vocabulary.** `rating_table.band` is a free-text `VARCHAR` today and the bands are whatever a publisher typed. Base rates key on the same vocabulary, so two spellings of `"18-25"` are two bands. Worth ratifying as reference data before the first real product is priced — a smaller version of the same void as the endorsement `changes` keys.
- **Policy fee.** Step 6 adds one, and no product field holds it. Either it becomes a product-version field or it is out of the calculation; it should not be a constant in code.
- **Occupation class source.** `FactorType.OCCUPATION_CLASS` exists, but `RiskProfile` explicitly excludes occupation for want of a structured source, and `MedicalDisclosure` has zero call sites. `premium-quote` takes `occupationClass` as an input from the caller, which is the honest interim position — but it means the value is asserted, not verified.
- **Audit actor.** Producing the compliance screen the mockup depicts needs actor, before, after and reason. That is a separate design decision about whether domain event payloads carry actor identity or a second mechanism records it.
- **Audit retention and partitions.** Both flagged in `V1__create_audit_schema.sql` and still open. Exposing the log for reading makes the retention question more urgent, not less.
