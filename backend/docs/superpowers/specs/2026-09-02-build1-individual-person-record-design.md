# Build 1 — the individual person record

Status: **built 2026-09-02.** First of six builds scoped from an underwriting
requirements review (§1–§5, §7 of the client's table). See
`frontend/PLAN.md` §14 for the design decisions this sits alongside, and §9 below
for what was found while building it.

---

## 1. Why this one first

`party.party` stores `display_name`, `date_of_birth`, `phone_number`, `email`,
`registration_number` and `kyc_status`. That is the whole person record.

The requirement (§2 Client / Life Assured Details) asks additionally for gender,
ID type and number, occupation, employer, address and nationality. But the
reason to do this **before** the other five builds is not the requirement — it is
that the platform's own pricing path already depends on data the platform
refuses to store:

```
product.base_rate_table   UNIQUE (product_version_id, age_from, sex, smoker_status)
PremiumQuoteRequest       requires sex, smokerStatus, occupationClass
party.party               stores none of the three
```

The rating engine's primary key is `(age, sex, smoker_status)`. Age is derivable
from `dateOfBirth`. The other two are **asserted by the caller on every quote and
then discarded**, so nothing on this platform records the risk facts a premium
was priced on. `product.api.Sex` and `product.api.SmokerStatus` both say so in
their own Javadoc, and `RiskProfile` documents that it excludes occupation and
smoker factors "for want of a structured source." This build is that source.

## 2. Scope

**In:** the individual person record — new columns, new API fields, registration
and detail read, the onboarding form and the client detail screen.

**Out:** anything that *consumes* the new fields. Wiring the quote into issuance
is Build 5; eligibility gates are Build 4. This build makes the data exist and
be readable, and stops there. Corporate and group parties are untouched.

## 3. Schema — `party/V2__individual_person_record.sql`

Thirteen nullable columns on `party.party`. Nullable is deliberate: every party
registered before this migration has none of them, and a `NOT NULL` column with
a backfilled default would invent facts about real people.

| Column | Type | Notes |
| --- | --- | --- |
| `sex` | `VARCHAR(10)` | `FEMALE`/`MALE` |
| `smoker_status` | `VARCHAR(20)` | `SMOKER`/`NON_SMOKER`/`UNKNOWN` |
| `id_type` | `VARCHAR(20)` | `NATIONAL_ID`/`PASSPORT`/`DRIVING_LICENCE`/`VOTER_ID` |
| `id_number` | `VARCHAR(50)` | |
| `occupation` | `VARCHAR(120)` | as declared, free text |
| `occupation_class` | `VARCHAR(30)` | the rating band, assigned by staff |
| `employer_name` | `VARCHAR(255)` | |
| `nationality` | `CHAR(2)` | ISO 3166-1 alpha-2 |
| `address_line` | `VARCHAR(255)` | |
| `ward` | `VARCHAR(100)` | |
| `district` | `VARCHAR(100)` | |
| `region` | `VARCHAR(100)` | |
| `postal_code` | `VARCHAR(20)` | |

Constraints:

- `CHECK` on each enumerated column, matching the existing table's style.
- `CHECK (nationality ~ '^[A-Z]{2}$')` when present.
- **Both-or-neither** on the identity document: `id_type` and `id_number` are
  either both null or both set. A number with no type is unreadable; a type with
  no number is noise.
- `CREATE UNIQUE INDEX ux_party_individual_identity ON party.party (tenant_id,
  id_type, id_number) WHERE id_number IS NOT NULL` — one national ID registers
  one person per tenant. This mirrors `ux_party_corporate_regno` exactly, and it
  matters more: a KYC register in which the same identity document can be
  registered twice cannot do the job it exists for.

`sex` and `smoker_status` deliberately get **no DB default.** A default of
`UNKNOWN` would apply to corporates and groups too, and would make "nobody asked"
indistinguishable from "the applicant declined to say."

## 4. Two enums, deliberately duplicated

`party` declares its own `Sex` and `SmokerStatus`, with literals identical to
`product`'s.

This is not an oversight. `party`'s module descriptor allows exactly
`document::api` and `refdata::api`; every module that depends on `product` does
so in the other direction (billing, distribution, finaccounting, policy,
underwriting). Making `party` depend on `product` to borrow two enums would
invert a dependency the modulith test enforces.

It is also the right model. In the party context these are **attributes of a
person**; in the product context they are **rating dimensions of a table**. They
happen to share literals today. The mapping belongs to whoever asks for a price —
`policy` and `underwriting` both already allow `party::api` and `product::api`,
so both can map without either module learning about the other.

Both enums carry Javadoc pointing at their counterpart so the next reader does
not "fix" the duplication.

## 5. API shape

`PartyApi.registerIndividual` currently takes five flat parameters and has
**about fifty call sites**, almost all in tests. Widening that signature is
churn with no reader benefit and a large merge surface.

Instead:

```java
PartyView registerIndividual(IndividualRegistration registration, String registeredBy);

/** Legacy five-argument form, kept so existing callers need no change. */
default PartyView registerIndividual(String fullName, LocalDate dateOfBirth,
        String phoneNumber, String email, String registeredBy) {
    return registerIndividual(IndividualRegistration.minimal(
        fullName, dateOfBirth, phoneNumber, email), registeredBy);
}
```

One implementation, no call-site churn, and the interface itself documents that
the flat form is a convenience over the real one.

`IndividualRegistration` groups the value objects rather than carrying seventeen
flat fields:

```java
record IndividualRegistration(
    String fullName, LocalDate dateOfBirth, String phoneNumber, String email,
    Sex sex, SmokerStatus smokerStatus, IdentityDocument identityDocument,
    String occupation, String occupationClass, String employerName,
    String nationality, Address address) {}

record IdentityDocument(IdType type, String number) {}   // both-or-neither, enforced in the compact constructor
record Address(String line, String ward, String district, String region, String postalCode) {}
```

The **entity stays flat columns**. Grouping is a contract concern; the existing
`Party` entity is flat and there is no reason to make persistence more elaborate
than the table.

`PartyDetailView` grows the same fields. **`PartyView` does not.** It stays four
fields for the reason already recorded on it: it is every row of a page and four
other modules read it as an existence check.

`DuplicateIdentityDocumentException` → `409 DUPLICATE_IDENTITY_DOCUMENT`,
registered in `PartyExceptionHandler` beside the corporate case, and thrown from
the same fast-path-check-plus-`saveAndFlush`-catch pattern
`registerCorporate` already uses — the index is the guarantee, the pre-check is
only a nicer error.

## 6. Screens

- **`OnboardCustomerPage`** (agents realm, and the staff path) grows the fields
  for individuals only. Grouped into *Identity*, *Person*, *Address* so the form
  does not become one flat run of thirteen inputs. Only full name and date of
  birth stay required, matching the spec's `required` list — an agent registering
  a walk-in should not be blocked on an employer's name.
- **`PartyDetailPage`** renders them in the existing `Field` label/value rows.
  Absent values render as the em dash, per the design system's own rule.

Nationality's input is pre-filled `TZ`. That is a visible default the user can
change, not a silent one written on their behalf.

## 7. What this deliberately does not do

- **No occupation-class reference list.** `refdata` holds nine numeric
  placeholder code sets and backs no dropdown anywhere. `occupation_class` is a
  free-text field validated at quote time against the product's own rating bands,
  not against a canonical list the platform would be inventing. Same reasoning as
  the disclosures resource: record what was declared; do not validate against a
  vocabulary we do not own.
- **No backfill.** Existing parties keep nulls.
- **No address normalisation.** One address per party, as columns. A party with
  several addresses (residential, postal, employer) is a real future need and a
  separate table when it arrives; five nullable columns now beat a premature
  join.
- **No use of the new data.** Builds 4 and 5 consume it.

## 8. Done gate

Backend unit/integration tests, the party contract test, `tsc`, `eslint`, the
Vitest suite, and the **full Playwright e2e suite** — this changes the party
read model and the onboarding form, both of which the e2e specs drive. Per
`frontend/PLAN.md` §14.5, rendering new fields on screens that specs assert
against is exactly the shape that breaks unscoped locators.

The dev database does not auto-apply migrations here: `V2` needs a manual `psql`
apply and a server restart before real-stack e2e reflects it.

---

## 9. Found while building

### 9.1 A transactional-boundary bug, and why only the audit log noticed

The legacy five-argument `registerIndividual` was first written as a `default`
method on `PartyApi` that delegated to the new two-argument form. That is wrong
in a way worth remembering.

Spring's transaction proxy intercepts calls to annotated methods. A `default`
interface method carries no annotation, so the proxy passed it straight to the
target — and the delegating call inside it was then a **self-invocation that
never re-entered the proxy**. Registration ran with no transaction at all.

The row still saved, because Spring Data's repository opens its own transaction.
What broke was quieter: `@TransactionalEventListener(AFTER_COMMIT)` silently
does nothing when no transaction is active, so `party.PartyRegistered` stopped
reaching the audit log — for every one of the ~50 legacy callers, with no error
anywhere. `registeringAnIndividualPublishesEventThatReachesAuditLog` was the only
thing that failed.

On a platform whose audit journal is a compliance artefact, "the write succeeded
but its event vanished" is the worst shape of bug available. The fix is to
declare the overload abstract and put `@Transactional` on the implementation, so
the delegation happens inside an already-open transaction. **The general rule:
a `default` interface method is never a safe place to put behaviour that needs
an annotation-driven proxy.**

### 9.2 `isPresent()` on a record serialises

`IdentityDocument.isPresent()` and `Address.isPresent()` were emitted by Jackson
as a phantom `"present": false` property, which failed contract validation
against the declared schema. Renamed to `recorded()` rather than annotating with
`@JsonIgnore`, which would have imported Jackson into an api package that has no
other reason to know it exists.

### 9.3 What "the tests pass" did and did not prove

Worth recording because the first green run was misleading. Passing Testcontainers
integration tests proved the migration applies, the entity maps, the constraints
bite, and the detail response matches the contract. It did **not** prove:

- the controller's hand-written twelve-field mapping (every field test called
  `PartyApi` directly, so a transposed `ward`/`district` would have passed — and
  unknown request keys are dropped platform-wide, so a misspelled property would
  have returned 201 and discarded the value);
- the `409`, which had an exception, a handler and a spec response but no test;
- the real authenticated path — the onboarding e2e passed while filling only the
  four pre-existing fields.

All three are now covered: `registeringWithThePersonRecordRoundTripsOverHttp` and
`aDuplicateIdentityDocumentIsRejectedWith409` in `PartyContractTest`, and an
agents-realm e2e that fills the new fields and re-reads them from the client
record via a separate `GET`.

### 9.4 Test-suite scope

The change reaches 40 of 97 backend test classes — the ones whose explicit
migration list needed `V2` added. The other 57 never touch `party`. Scoped runs
of those 40 take ~8 minutes against ~22 for the full suite; the full suite is
worth running once as a merge gate, not per iteration. Mean class time is 12.2s,
almost all of it Testcontainers plus a Spring context per class, because
`@DynamicPropertySource` gives every class a distinct context cache key.

### 9.5 Spec-truth fix picked up on the way

`POST /parties/corporates` has returned `409 DUPLICATE_REGISTRATION_NUMBER` since
M1 and the OpenAPI document never declared it. Declared now, alongside the new
`409` on `/parties/individuals`.
