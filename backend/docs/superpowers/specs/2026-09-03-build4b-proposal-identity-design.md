# Build 4b — proposal identity and the life assured

Status: **built 2026-09-03.** The half of Build 4 deferred out of the gates
commit. Closes §1 of the underwriting requirements review.

---

## 1. Two problems

**A case could not be referred to.** `underwriting_case` was identified by a bare
UUID. That is why the queue was unreadable before parties resolved to names, and
it stayed true of the case itself — nobody can quote a case reference over the
phone. `proposal_number` is the human handle, shaped like `policy_number`.

**A proposal where the policyholder insures somebody else could not be expressed
at all.** One `applicant_party_id` was the whole party model. Most life business
is not self-insured — a parent insures a child, a spouse a spouse, an employer
its staff — and both still-unbuilt contexts *require* the distinction: group
business is definitionally one policyholder and many lives assured, and credit
life is a lender-driven policy on a borrower's life. Neither §4 nor §5 could have
been modelled correctly without this landing first, whichever way their open
questions are answered.

It also unblocks something the design critique found: contestability review, the
task `PRODUCT.md` names as the claims assessor's job, has no working surface
partly because the declarations hang off a case reachable only when the claimant
*is* the life assured — which on a death claim they generally are not.

## 2. Schema

`underwriting/V4__proposal_identity.sql` adds `proposal_number`,
`life_assured_party_id`, `branch`, `source_of_business` and
`proposed_commencement_date`. `policy/V7__life_assured.sql` adds
`life_assured_party_id` to the contract itself, because a death claim is assessed
against the life assured while the policyholder is who pays and who holds the
rights.

### The backfill, and why it is not inconsistent with Builds 1 and 2

Both earlier builds refused to backfill, on the grounds that a default would
invent facts. This one backfills, and the difference is real rather than
convenient.

Sex, occupation and policy term were facts **nobody had recorded**. Any value
would have been fabrication.

The life assured **was recorded** — as `applicant_party_id` — because the model
had exactly one party slot and that slot is unambiguously the life whose
mortality is rated: `ProductApiImpl` derives entry age from the applicant's date
of birth, and `RiskProfile` is built from the applicant. Copying it forward
states what those rows already meant.

Result on the dev database: **227 cases and 629 policies backfilled, none left
null.** The column is immediately useful rather than being a mostly-empty field
nothing can rely on.

### `source_of_business` is free text on purpose

Not a CHECK-constrained enum. The platform does not own this vocabulary — TIRA's
return catalogue is still an open item — and regreporting will eventually need
whatever list the regulator publishes. Minting one here would make it the de
facto schema in a column nobody could later ratify, which is exactly how the
endorsement `changes` payload and the M7 commission semantics became voids.

### `proposal_number` is random, not sequential

`PRO-XXXXXXXX`, mirroring how `policy_number` is already minted. A per-tenant
sequence would read better (`PRO-2026-000123`) but a shared sequence leaks case
volume across tenants, and the actual requirement is something a person can quote
over the phone — which the random form satisfies and a bare UUID does not.
`ux_underwriting_case_proposal_number` is the uniqueness guarantee.

## 3. Self-insured is resolved, not stored as null

A caller that names no life assured means "the applicant insures themselves", and
the service resolves that to the applicant before saving. The column therefore
always answers "whose life is this" rather than carrying a null every later
reader has to interpret — and a null now means only "opened before the column
existed".

The life assured is validated the same way the applicant is, and only when the
two differ: a case naming a life assured who does not exist in this tenant is
unassessable, and `PartyApi.getParty` already refuses cross-tenant reads.

## 4. Screens

- **Underwriting queue**: the identifying column is the proposal number, falling
  back to the case id for pre-migration cases with a tooltip saying why. An em
  dash where a row's identity belongs would be worse than the old UUID.
- **Case detail**: proposal number, applicant, life assured, and the source
  fields when present. The applicant row gains a note when the two parties
  differ, because that is the case an underwriter needs to notice.
- **Policy detail**: life assured rendered **only when it differs** from the
  policyholder. On a self-insured policy a second row repeating the same name is
  noise, and noise is what teaches people to skip a panel.
- **Both forms** gained a life-assured picker, labelled "leave blank if the
  applicant/policyholder insures themselves".
- Two labels renamed from column names to meanings: "Policyholder party id" →
  "Policyholder", "Applicant party id" → "Applicant". Labels named after DB
  columns were a P0 in the design critique, and these are the two forms where the
  distinction now matters.

## 5. Notes from building it

### 5.1 The clean gate earned its place

`ManualIssueRequestDto` was widened again here, and
`PolicyNegativeAmountDefenceTest` — the same file, for the second time — broke.
This time `./mvnw -o clean test` caught it before a commit rather than two
commits later. The check that finds these directly is
`grep -rn "new <TypeName>(" src`, run **after** widening rather than before, and
it is now part of the routine for any signature change.

### 5.2 A documented trap, nearly reintroduced

`lifeAssuredPartyId` was first written into the OpenAPI schema as `oneOf` with a
`{type: "null"}` branch. `PLAN.md` §10 item 3 records that spelling making
`swagger-request-validator` match **every** value — silently switching validation
off while the tests stay green. Corrected to the sanctioned `allOf`-wrapped
`$ref` plus `nullable: true` that the platform's other nullable `$ref` fields use.

Worth noting how close it came: the wrong spelling compiles, generates correct
TypeScript, and passes every test. Only the note in `PLAN.md` distinguishes it.

### 5.3 Ordering caught by reading the form back

The optional Source group first landed between the life-assured picker and the
product selector, interrupting the required flow. Moved to last: the risk — who,
what product, how much — is what the form is for.

## 6. Still open

- **`claimGates` still judges risk commencement against `issueDate`** rather than
  Build 2's `commencementDate`, and now also has a life assured it could use.
  Deferred again deliberately: it changes an existing gate's behaviour and
  deserves its own verification pass.
- **Relationship to policyholder** (§2's last field) is not recorded. The
  `party_relationship` table exists with a free-text `relationship_type` and no
  writer. Recording it properly means ratifying a relationship vocabulary, which
  is the same trap as `source_of_business` and deserves the same restraint.
- Nothing yet uses `life_assured_party_id` to answer the contestability question.
  The index is there; the surface is not.
