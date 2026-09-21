# Group Scheme Substitution and Notices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a group scheme a fixed-headcount contract whose lives can be substituted but never added to, whose members may be named without registering them as parties, whose employer is told when the scheme goes live and when the schedule changes, and whose invoices are visible on the scheme page.

**Architecture:** Five independent deliverables against the existing `policy` group-scheme model. Members gain a `PARTY`/`FREEFORM` designation mirroring `policy.beneficiary` exactly, so a 500-life schedule does not force 500 party registrations into the KYC queue. `addMember` is retired and replaced by a substitution that exits the outgoing life and admits the incoming one in a single transaction, which leaves headcount and premium untouched. Two new employer-facing templates hang off two enriched domain events, consumed by `communication` (which may not depend on `policy`, so everything the message needs travels in the payload). `party.group_membership` — a second, unused member model with zero rows — is deleted.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Flyway (per-module directories, applied by `scripts/migrate.sh`, NOT on boot), Postgres 16, Testcontainers, JUnit 5, AssertJ; React + TypeScript, Zustand, zod, Vitest, Playwright.

## Global Constraints

- **A scheme's headcount is fixed at issuance.** No life may be added after the scheme is issued. The only movement is substitution: one life out, one life in, same instant, same count.
- **Substitution does not change the premium.** `premiumAmount` is agreed for the period and is not re-rated by member movement. The scheme's *sum assured* may move if the substitute's grade or salary differs — that is correct, because the contract total is the sum of the schedule.
- **Only the employer (the policyholder) is notified.** Members are never messaged. This is a business decision, not an implementation limit — do not add member messaging "while you are in there".
- **Premium-due and premium-overdue messaging is explicitly OUT of scope**, deferred to a later build that will also carry a staff approval surface for automatic sending. No template, listener or flag for it in this plan.
- Every new `policy` migration must be appended to the migration list in all **37** test classes that carry one; every new `party` migration to all **45**. A migration in no test class has never run in any test schema (this bit us at `policy/V10`).
- `communication` may depend on `party::api` and `refdata::api` only (`communication/package-info.java`). It must never read from `policy`; everything a message needs arrives in the event payload.
- Migrations are NOT applied on boot (`spring.flyway.enabled: false`). After each migration, apply it to the dev database by hand before any real-stack check: `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/<mod>/<file>.sql`
- Do not run Prettier. Do not run Maven inside Docker. After any record or method-signature change run `./mvnw -o clean test-compile` — incremental compilation hides broken unchanged tests.
- Money is `NUMERIC(19,2)`; rating multipliers are `NUMERIC(9,4)`. Never render an amount without its currency.

---

## File Structure

**Backend — created**

| File | Responsibility |
|---|---|
| `db-migrations/policy/V13__freeform_members.sql` | `member_type`, `member_name`, `member_date_of_birth`; `member_party_id` becomes nullable; the exactly-one-designation CHECK |
| `db-migrations/party/V4__drop_group_membership.sql` | Drops the unused second member model |
| `db-migrations/communication/V10__group_scheme_templates.sql` | `GROUP_SCHEME_LIVE` and `GROUP_MEMBER_REPLACED`, 4 rows each |
| `src/main/java/tz/co/nlolo/lifeplatform/policy/api/MemberType.java` | `PARTY` / `FREEFORM` |
| `src/main/java/tz/co/nlolo/lifeplatform/communication/application/GroupSchemeEventListener.java` | Turns the two group events into employer messages |

**Backend — modified**

| File | Change |
|---|---|
| `policy/api/PolicyApi.java` | `MemberInput` grows a designation; `addMember` removed; `replaceMember` added |
| `policy/api/PolicyMemberView.java` | Carries `memberType` and `memberName` |
| `policy/domain/PolicyMember.java` | Freeform identity fields; constructor takes them |
| `policy/application/PolicyApiImpl.java` | Designation validation, FCL-freeform refusal, `replaceMember`, enriched event payloads |
| `policy/infrastructure/PolicyMemberRepository.java` | Name search covers freeform names |
| `policy/infrastructure/PolicyController.java` | `POST .../members` removed; `POST .../members/{id}/replacement` added |
| `policy/infrastructure/GroupMemberInputDto.java`, `PolicyMemberResponseDto.java` | Wire shape for the new fields |
| `party/api/PartyApi.java`, `party/application/PartyApiImpl.java`, `party/infrastructure/PartyController.java` | Group-membership methods and endpoints deleted |
| `api/openapi/openapi-policy.yaml`, `api/openapi/openapi-party.yaml` | Contract follows the code |
| `api/asyncapi-events.yaml` | `GroupSchemeIssued` and `GroupMemberReplaced` documented (neither is catalogued today) |

**Backend — deleted**

`party/domain/GroupMembership.java`, `party/api/GroupMembershipView.java`, `party/infrastructure/GroupMembershipRepository.java`, `party/infrastructure/AddGroupMemberRequest.java`

**Frontend — modified**

| File | Change |
|---|---|
| `src/features/policies/GroupSchemePage.tsx` | Invoices panel; substitution replaces the add form; freeform member rows render their own name |
| `src/features/policies/addMemberForm.ts` (+ `.test.ts`) | Becomes `substituteMemberForm`; a person-or-name choice |
| `src/api/policies.ts`, `src/store/policyStore.ts` | `addSchemeMember` becomes `substituteSchemeMember` |
| `e2e/staff-group-schemes.spec.ts` | The "joiner" test becomes a substitution test; a freeform test is added |

---

## Task 1: Invoices on the scheme page

Independent of every other task — frontend only, no API change. `InvoicesPanel` is already self-contained: it takes a `policyNumber` and owns its store slice.

**Files:**
- Modify: `frontend/src/features/policies/GroupSchemePage.tsx`
- Test: `frontend/e2e/staff-group-schemes.spec.ts`

**Interfaces:**
- Consumes: `InvoicesPanel({ policyNumber }: { policyNumber: string })` from `./InvoicesPanel`, and `Panel` from `@/components/Panel` — both already used on `PolicyDetailPage`.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Write the failing e2e assertion**

Add to the existing `sets up a scheme whose total is derived from its members` test in `frontend/e2e/staff-group-schemes.spec.ts`, after the scheme page has loaded:

```ts
    // An employer's schedule and an employer's bill are the same conversation.
    // Before this the only way to see whether ABC Company had paid was to leave the
    // scheme, open the master policy, and read the Invoices panel there.
    await expect(page.getByRole('heading', { name: 'Invoices' })).toBeVisible();
    await expect(page.getByText('Every premium raised against this scheme')).toBeVisible();
```

- [ ] **Step 2: Run it and watch it fail**

```
cd frontend && npx playwright test staff-group-schemes.spec.ts -g "derived from its members"
```

Expected: FAIL — no `Invoices` heading on the page.

- [ ] **Step 3: Add the panel**

In `GroupSchemePage.tsx`, add the import beside the existing ones:

```tsx
import { InvoicesPanel } from './InvoicesPanel';
```

and render it inside `<DetailLayout>`, immediately after the closing `</section>` of the Members table:

```tsx
        {/* The employer's bill, on the employer's page. `InvoicesPanel` is keyed by
            policy number and owns its own store slice, so a scheme reads exactly
            the same invoices the master policy does -- there is one billing
            schedule and this is a second window onto it, not a second copy. */}
        <Panel title="Invoices" subtitle="Every premium raised against this scheme">
          <InvoicesPanel policyNumber={policyNumber} />
        </Panel>
```

- [ ] **Step 4: Run the e2e test and the unit suite**

```
cd frontend && npx playwright test staff-group-schemes.spec.ts -g "derived from its members" ; npm run typecheck ; npm run lint
```

Expected: PASS, clean typecheck, clean lint.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/policies/GroupSchemePage.tsx frontend/e2e/staff-group-schemes.spec.ts
git commit -m "feat(console): show a scheme's invoices on the scheme page"
```

---

## Task 2: Retire `party.group_membership`

A second member model with an entity, a repository, two REST endpoints, an OpenAPI schema and **zero rows in the dev database and no caller anywhere in the codebase or console**. Group membership belongs to `policy`, where the benefit, the FCL and the claim all live.

**Files:**
- Create: `backend/db-migrations/party/V4__drop_group_membership.sql`
- Delete: `backend/src/main/java/tz/co/nlolo/lifeplatform/party/domain/GroupMembership.java`, `party/api/GroupMembershipView.java`, `party/infrastructure/GroupMembershipRepository.java`, `party/infrastructure/AddGroupMemberRequest.java`
- Modify: `party/api/PartyApi.java`, `party/application/PartyApiImpl.java`, `party/infrastructure/PartyController.java`, `backend/api/openapi/openapi-party.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/party/PartyApiIntegrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. `PartyApi.addGroupMember(UUID, UUID)` and `PartyApi.listGroupMembers(UUID, Pageable)` cease to exist.

- [ ] **Step 1: Delete the tests that pin the behaviour**

In `PartyApiIntegrationTest.java`, delete every test method that calls `addGroupMember` or `listGroupMembers`, and any `registerGroup` fixture used only by them. Find them with:

```
cd backend && grep -n "addGroupMember\|listGroupMembers" src/test/java/tz/co/nlolo/lifeplatform/party/PartyApiIntegrationTest.java
```

- [ ] **Step 2: Write the migration**

`backend/db-migrations/party/V4__drop_group_membership.sql`:

```sql
-- db-migrations/party/V4__drop_group_membership.sql
-- One member model, not two.
--
-- party.group_membership shipped with an entity, a repository, two REST endpoints and an
-- OpenAPI schema. It has never held a row, in dev or anywhere else, and nothing in the
-- backend or the console has ever called it. Meanwhile policy.policy_member -- which knows
-- the benefit basis, the free cover limit, the effective-dated benefit and the underwriting
-- state -- is what "a member" actually means on this platform.
--
-- Two models for one concept is not redundancy, it is an invitation: the next person to need
-- "who is in this group" picks whichever they find first, and half the platform answers a
-- different question from the other half. Deleting the one with no rows and no callers costs
-- nothing today and is impossible once somebody writes to it.
--
-- Dropped rather than deprecated because there is nothing to migrate. If group membership
-- ever needs to exist independently of a policy -- a SACCO roll that predates its cover --
-- it should be designed then, against that requirement, not inherited from this stub.
DROP TABLE IF EXISTS party.group_membership;
```

- [ ] **Step 3: Delete the code**

```bash
cd backend
rm src/main/java/tz/co/nlolo/lifeplatform/party/domain/GroupMembership.java
rm src/main/java/tz/co/nlolo/lifeplatform/party/api/GroupMembershipView.java
rm src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/GroupMembershipRepository.java
rm src/main/java/tz/co/nlolo/lifeplatform/party/infrastructure/AddGroupMemberRequest.java
```

Then remove from `PartyApi.java` the `addGroupMember` and `listGroupMembers` declarations and their javadoc; from `PartyApiImpl.java` the two implementations, the `groupMembershipRepository` field, its constructor parameter and its import; from `PartyController.java` the two handler methods and the `GroupMembershipView` / `AddGroupMemberRequest` imports.

- [ ] **Step 4: Remove the endpoints from the contract**

In `backend/api/openapi/openapi-party.yaml`, delete the `/parties/{partyId}/groups/{groupId}/group-members` path item (both operations) and the `GroupMembershipView` schema. Find them with:

```
cd backend && grep -n "group-members\|GroupMembershipView" api/openapi/openapi-party.yaml
```

- [ ] **Step 5: Add the migration to every test class that lists party migrations**

45 test classes carry the list. Insert the new file after `V3__rls_fail_closed.sql` using a pattern-only node script (heredocs and shell string editing corrupt these files — CRLF endings defeat `\n` matching):

```js
// scratchpad/add-party-v4.js
const fs = require('fs');
const AFTER = 'db-migrations/party/V3__rls_fail_closed.sql';
const NEW = 'db-migrations/party/V4__drop_group_membership.sql';
let changed = 0;
for (const f of process.argv.slice(2)) {
  const src = fs.readFileSync(f, 'utf8');
  const eol = src.includes('\r\n') ? '\r\n' : '\n';
  const lines = src.split(eol);
  const i = lines.findIndex((l) => l.includes(AFTER));
  if (i < 0) { console.log('NO ANCHOR: ' + f); continue; }
  if (lines.some((l) => l.includes(NEW))) { console.log('ALREADY: ' + f); continue; }
  const line = lines[i];
  const indent = line.match(/^\s*/)[0];
  const tail = line.endsWith(',') ? '' : line.slice(line.lastIndexOf('"') + 1);
  const anchorLine = tail === '' ? line : line.slice(0, line.lastIndexOf('"') + 1) + ',';
  lines.splice(i, 1, anchorLine, indent + '"' + NEW + '"' + (tail === '' ? ',' : tail));
  fs.writeFileSync(f, lines.join(eol));
  changed++;
}
console.log('changed ' + changed + ' of ' + (process.argv.length - 2));
```

```bash
cd backend && node ../scratchpad/add-party-v4.js $(grep -rl "db-migrations/party/V3__rls_fail_closed.sql" src/test/java)
```

Then confirm the count matches: `grep -rl "V4__drop_group_membership" src/test/java | wc -l`

- [ ] **Step 6: Compile clean and run the affected tests**

```
cd backend && ./mvnw -o clean test-compile && ./mvnw -o test -Dtest='PartyApiIntegrationTest+PartyContractTest' -DfailIfNoTests=false
```

Expected: BUILD SUCCESS, no compile errors from the deleted symbols.

- [ ] **Step 7: Apply to the dev database and commit**

```bash
cd backend
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/party/V4__drop_group_membership.sql
git add -A backend/db-migrations/party backend/src/main/java/tz/co/nlolo/lifeplatform/party backend/src/test/java backend/api/openapi/openapi-party.yaml
git commit -m "refactor(party): delete the unused second group-membership model"
```

---

## Task 3: Members may be named without being registered

A member becomes `PARTY` (a real person in the register) or `FREEFORM` (a name on the schedule), exactly as `policy.beneficiary` already is. The reason is concrete: registering 500 employees puts 500 rows at `kyc_status = 'PENDING'` into the Clients screen, which is built as the KYC review queue with a nav badge. Group premium is not individually rated — `issueGroupScheme` takes `premiumAmount` from the caller and a member's benefit comes from the scheme basis — so a name plus a salary-or-grade values a member completely.

**Files:**
- Create: `backend/db-migrations/policy/V13__freeform_members.sql`, `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/MemberType.java`
- Modify: `policy/api/PolicyApi.java`, `policy/api/PolicyMemberView.java`, `policy/domain/PolicyMember.java`, `policy/application/PolicyApiImpl.java`, `policy/infrastructure/PolicyMemberRepository.java`, `policy/infrastructure/GroupMemberInputDto.java`, `policy/infrastructure/PolicyMemberResponseDto.java`, `backend/api/openapi/openapi-policy.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/GroupSchemeIntegrationTest.java`

**Interfaces:**
- Consumes: `GroupBenefitCalculator.Valuation(BigDecimal benefitAmount, BigDecimal coveredAmount, MemberUnderwritingStatus underwritingStatus)` — unchanged, it never needed a party.
- Produces, for Tasks 4–7:
  - `enum MemberType { PARTY, FREEFORM }` in `policy.api`
  - `record MemberInput(MemberType memberType, UUID memberPartyId, String memberName, LocalDate memberDateOfBirth, String gradeCode, BigDecimal salaryAmount, LocalDate joinedOn)`
  - `record PolicyMemberView(UUID policyMemberId, MemberType memberType, UUID memberPartyId, String memberName, String gradeCode, LocalDate joinedOn, LocalDate leftOn, MemberStatus status, MemberUnderwritingStatus underwritingStatus, UUID underwritingCaseId, BigDecimal salaryAmount, BigDecimal benefitAmount, BigDecimal coveredAmount, String currency, LocalDate benefitEffectiveFrom)` — `memberName` is the stored name on a FREEFORM member and **null on a PARTY member**, whose name the console already resolves through `<PartyName partyId=… />`
  - `PolicyApiImpl.displayNameOf(PolicyMember)` — the name for a message or an event payload, resolving a PARTY member through `partyApi`

- [ ] **Step 1: Write the failing tests**

Add to `GroupSchemeIntegrationTest.java`. It already has the fixtures these use — `groupProduct(String code)` returning a `GroupProduct(productId, productVersionId)`, `person(String name)` returning a party id, and `flatScheme(GroupProduct, UUID employer, BigDecimal flatBenefit, BigDecimal fcl, List<MemberInput> members)` which fixes the premium at 1,200,000 ANNUALLY and commences today. Use them; do not invent new ones.

```java
    @Test
    void aSchemeMayNameALifeWithoutRegisteringThemAsAParty() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FREEFORM-1");
        UUID employer = person("ABC Company");

        GroupSchemeView scheme = policyApi.issueGroupScheme(
            flatScheme(product, employer, new BigDecimal("5000000.00"), null,
                List.of(new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Zawadi Mushi",
                    LocalDate.of(1991, 4, 2), null, null, null))),
            "staff1");

        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo(new BigDecimal("5000000.00"));

        assertThat(policyApi.listMembers(scheme.policyNumber(), null, null, PageRequest.of(0, 25))
            .getContent()).singleElement().satisfies(m -> {
                assertThat(m.memberType()).isEqualTo(MemberType.FREEFORM);
                assertThat(m.memberName()).isEqualTo("Zawadi Mushi");
                assertThat(m.memberPartyId())
                    .as("no party row is created for a freeform life -- that is the entire point")
                    .isNull();
                assertThat(m.coveredAmount()).isEqualByComparingTo(new BigDecimal("5000000.00"));
            });
    }

    @Test
    void aFreeformLifeAboveTheFreeCoverLimitIsCoveredToTheLimitLikeAnyOther() {
        // NOT refused. A member is a dependent record on the policy -- often literally a
        // dependant, a spouse or a child -- and a child has no KYC to produce. Turning one
        // away at the door because the scheme's benefit happens to exceed its free cover
        // limit would refuse cover the employer has bought, over paperwork the life will
        // never have.
        //
        // Nothing is lost by admitting them, and this test pins that. Over the limit already
        // means covered AT the limit with the excess ungranted, for every member: covered
        // 100m against a 150m benefit. And referForEvidence has no caller anywhere -- no
        // underwriting case is opened for ANY above-FCL member today -- so a freeform life
        // here is in exactly the position a registered one is, not a worse one.
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FREEFORM-FCL");
        UUID employer = person("ABC Company");

        GroupSchemeView scheme = policyApi.issueGroupScheme(
            flatScheme(product, employer, new BigDecimal("150000000.00"), new BigDecimal("100000000.00"),
                List.of(new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Zawadi Mushi",
                    null, null, null, null))),
            "staff1");

        assertThat(policyApi.listMembers(scheme.policyNumber(), null, null, PageRequest.of(0, 25))
            .getContent()).singleElement().satisfies(m -> {
                assertThat(m.benefitAmount()).isEqualByComparingTo(new BigDecimal("150000000.00"));
                assertThat(m.coveredAmount())
                    .as("covered to the limit while the excess is ungranted -- the figure a claim pays")
                    .isEqualByComparingTo(new BigDecimal("100000000.00"));
                assertThat(m.underwritingStatus()).isEqualTo(MemberUnderwritingStatus.EVIDENCE_REQUIRED);
            });
    }

    @Test
    void aMemberMustCarryExactlyOneDesignation() {
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FREEFORM-BOTH");
        UUID employer = person("ABC Company");

        assertThatThrownBy(() -> policyApi.issueGroupScheme(
            flatScheme(product, employer, new BigDecimal("5000000.00"), null,
                List.of(new PolicyApi.MemberInput(MemberType.PARTY, null, "Zawadi Mushi",
                    null, null, null, null))),
            "staff1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("must name a registered person");
    }

    @Test
    void theRollIsSearchableByAFreeformNameAsWellAsARegisteredOne() {
        // The name search resolved through the party module alone. A freeform member has no
        // party, so an id-only search made half the schedule unfindable -- on the screen whose
        // whole purpose is answering "is this person covered".
        TenantContext.set(UUID.randomUUID());
        GroupProduct product = groupProduct("GRP-FREEFORM-SEARCH");
        UUID employer = person("ABC Company");
        UUID registered = person("Neema Registered");

        GroupSchemeView scheme = policyApi.issueGroupScheme(
            flatScheme(product, employer, new BigDecimal("5000000.00"), null,
                List.of(new PolicyApi.MemberInput(MemberType.PARTY, registered, null, null, null, null, null),
                        new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Zawadi Mushi", null, null, null, null))),
            "staff1");

        assertThat(policyApi.listMembers(scheme.policyNumber(), null, "Zawadi", PageRequest.of(0, 25))
            .getContent()).singleElement()
            .satisfies(m -> assertThat(m.memberName()).isEqualTo("Zawadi Mushi"));
        assertThat(policyApi.listMembers(scheme.policyNumber(), null, "Neema", PageRequest.of(0, 25))
            .getContent()).singleElement()
            .satisfies(m -> assertThat(m.memberPartyId()).isEqualTo(registered));
        assertThat(policyApi.listMembers(scheme.policyNumber(), null, "Nobody", PageRequest.of(0, 25))
            .getContent())
            .as("a search matching no party AND no freeform name is empty, not the whole roll")
            .isEmpty();
    }
```

- [ ] **Step 2: Run them and watch them fail**

```
cd backend && ./mvnw -o test -Dtest='GroupSchemeIntegrationTest#aSchemeMayNameALifeWithoutRegisteringThemAsAParty' -DfailIfNoTests=false
```

Expected: FAIL to compile — `MemberType` does not exist and `MemberInput` has four components.

- [ ] **Step 3: Write the migration**

`backend/db-migrations/policy/V13__freeform_members.sql`:

```sql
-- db-migrations/policy/V13__freeform_members.sql
-- A life on a schedule is not necessarily a client in the register.
--
-- policy_member.member_party_id was UUID NOT NULL, so every insured employee had to be
-- registered as a party first. A 500-life schedule therefore meant 500 party rows, every one
-- of them landing at kyc_status = 'PENDING' -- in a Clients screen that IS the KYC review
-- queue, with a nav badge answering "what needs me today". Five hundred rows nobody will ever
-- action makes that badge meaningless, and turns a register of people the tenant has a
-- relationship with into a payroll dump. The employer is the client; their staff are lives on
-- a schedule.
--
-- So a member is designated exactly the way a beneficiary already is (see
-- chk_beneficiary_exactly_one_designation in V1): PARTY names a registered person, FREEFORM
-- carries its own name. This is not a new pattern, it is the existing one applied to the
-- other table that needed it.
--
-- Group premium is what makes this safe. issueGroupScheme takes premiumAmount from the caller
-- and a member's benefit comes from the scheme basis -- flat, salary x multiple, or grade.
-- Nothing about pricing a group member consults their age or their health, so a name plus a
-- salary-or-grade values them completely.
--
-- AND A MEMBER IS OFTEN NOT AN EMPLOYEE AT ALL. Schemes cover dependants: a spouse, a child.
-- A child has no national ID, no phone and no KYC, and never will. Any design that requires a
-- registered person per insured life cannot express half of what these contracts actually
-- cover -- which is the same reason policy.beneficiary has carried FREEFORM since V1.
--
-- WHAT A FREEFORM LIFE CANNOT DO: be a claimant in their own right, or answer "which schemes
-- is this person covered on". Both are promotion points rather than losses -- a freeform
-- member becomes a party at the moment one of them is needed.
--
-- NOT on that list: being above the free cover limit. An earlier draft of this change refused
-- a freeform life over the limit, on the grounds that the excess needs an underwriting case
-- and a case needs a person. That was wrong twice over. policy_member.underwriting_status
-- already handles it correctly -- covered AT the limit, excess ungranted, row flagged
-- EVIDENCE_REQUIRED -- and PolicyMember.referForEvidence has no caller, so NO above-FCL member
-- gets a case opened today, registered or not. The rule would have refused a freeform life for
-- lacking something a party member never gets either, and would have withheld cover the
-- employer has already bought.
ALTER TABLE policy.policy_member
    ADD COLUMN member_type          VARCHAR(10) NOT NULL DEFAULT 'PARTY'
        CHECK (member_type IN ('PARTY','FREEFORM')),
    ADD COLUMN member_name          VARCHAR(255),
    ADD COLUMN member_date_of_birth DATE;

-- The DEFAULT exists only to backfill the rows already there, every one of which genuinely is
-- a registered person. Dropped immediately after, so no future insert can acquire a
-- designation by omission -- matching policy.beneficiary, whose beneficiary_type has none.
ALTER TABLE policy.policy_member ALTER COLUMN member_type DROP DEFAULT;

ALTER TABLE policy.policy_member ALTER COLUMN member_party_id DROP NOT NULL;

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exactly_one_designation CHECK (
        (member_type = 'PARTY'    AND member_party_id IS NOT NULL AND member_name IS NULL)
     OR (member_type = 'FREEFORM' AND member_name     IS NOT NULL AND member_party_id IS NULL)
    );

COMMENT ON COLUMN policy.policy_member.member_name IS
    'The life''s name, on a FREEFORM member only. A PARTY member''s name lives in party.party '
    'and must never be copied here, or the two can disagree about who is insured.';

-- ux_policy_member_active is (policy_number, member_party_id) WHERE status = 'ACTIVE'. It is
-- deliberately left alone. Postgres treats NULLs as distinct in a unique index, so any number
-- of freeform members coexist on one scheme while registered people still cannot be admitted
-- twice. That asymmetry is correct rather than convenient: two freeform rows reading "John
-- Mushi" might be two employees with the same name, and the database cannot know. Identity is
-- exactly what a party id buys, and exactly what a freeform member has not bought.
```

- [ ] **Step 4: Add the migration to the 37 test classes that list policy migrations**

Reuse the script from Task 2 with different constants (`AFTER = 'db-migrations/policy/V11__not_taken_up_status.sql'`, `NEW = 'db-migrations/policy/V13__freeform_members.sql'`). `V12__rls_fail_closed.sql` is deliberately absent from the test lists; anchor on V11.

```bash
cd backend && node ../scratchpad/add-policy-v13.js $(grep -rl "db-migrations/policy/V11__not_taken_up_status.sql" src/test/java)
grep -rl "V13__freeform_members" src/test/java | wc -l   # expect 37
```

- [ ] **Step 5: Add the enum**

`backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/MemberType.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

/**
 * How a life on a schedule is identified.
 *
 * <p>Deliberately the same shape as {@link BeneficiaryType}, because it is the same problem:
 * some of the people a contract names are clients of this tenant and some are simply named.
 * A PARTY member is a registered person, and everything the platform can do to a person --
 * underwrite them, pay them a claim, list every scheme they are on -- is available. A FREEFORM
 * member is a name and a benefit on one schedule.
 *
 * <p><b>A member is not necessarily an employee.</b> Schemes cover dependants: a spouse, a
 * child. A child has no national ID, no phone and no KYC, and never will — so a model that
 * demands a registered person per insured life cannot express what these contracts actually
 * cover. That is the same reason beneficiaries have had FREEFORM since the first migration.
 *
 * <p>FREEFORM carries no restriction of its own: a freeform life above the scheme's free cover
 * limit is admitted and covered to the limit, exactly as a registered one is. It is a starting
 * point rather than a lesser category — a member is promoted to PARTY at the moment something
 * needs the person rather than the name.
 */
public enum MemberType { PARTY, FREEFORM }
```

- [ ] **Step 6: Widen `MemberInput` and `PolicyMemberView`**

In `policy/api/PolicyApi.java`, replace the `MemberInput` record with:

```java
    /**
     * One life, on the opening schedule or substituted in later.
     *
     * @param memberType PARTY for a registered person, FREEFORM for a name on the schedule.
     * @param memberPartyId required on PARTY, rejected on FREEFORM.
     * @param memberName required on FREEFORM, rejected on PARTY -- a PARTY member's name lives
     *     in the party module, and a copy here is a second answer to "who is insured".
     * @param memberDateOfBirth optional, and only meaningful on a FREEFORM member. It buys
     *     nothing in pricing (a group member's benefit comes from the scheme basis, never from
     *     their age) and everything in telling two people of the same name apart on a claim.
     * @param gradeCode required on a GRADED scheme, and rejected on any other -- a grade on a
     *     flat scheme is a caller who believes something about the contract that is not true.
     * @param salaryAmount required on a SALARY_MULTIPLE scheme, and rejected on any other.
     * @param joinedOn when cover starts for this member. Null means the scheme's commencement
     *     date, which is what an opening-schedule row means.
     */
    record MemberInput(MemberType memberType, UUID memberPartyId, String memberName,
                        LocalDate memberDateOfBirth, String gradeCode, BigDecimal salaryAmount,
                        LocalDate joinedOn) {}
```

In `policy/api/PolicyMemberView.java`, add `memberType` as the second component and `memberName` as the fourth, and document the null rule:

```java
 * @param memberName the life's own name on a FREEFORM member, and NULL on a PARTY member --
 *     whose name lives in the party module and is resolved by the reader. Null here therefore
 *     means "ask party", never "nameless".
```

- [ ] **Step 7: Carry the fields on the entity**

In `policy/domain/PolicyMember.java`, add the three columns and widen the constructor:

```java
    @Column(name = "member_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private MemberType memberType;

    @Column(name = "member_name")
    private String memberName;

    @Column(name = "member_date_of_birth")
    private LocalDate memberDateOfBirth;
```

```java
    public PolicyMember(UUID tenantId, String policyNumber, MemberType memberType,
                         UUID memberPartyId, String memberName, LocalDate memberDateOfBirth,
                         String gradeCode, LocalDate joinedOn,
                         MemberUnderwritingStatus underwritingStatus, String createdBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.memberType = memberType;
        this.memberPartyId = memberPartyId;
        this.memberName = memberName;
        this.memberDateOfBirth = memberDateOfBirth;
        this.gradeCode = gradeCode;
        this.joinedOn = joinedOn;
        this.status = "ACTIVE";
        this.underwritingStatus = underwritingStatus;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }
```

plus `getMemberType()`, `getMemberName()`, `getMemberDateOfBirth()`.

- [ ] **Step 8: Validate the designation and refuse a freeform life above the FCL**

In `PolicyApiImpl.java`, add beside the existing member helpers:

```java
    /**
     * A member names exactly one thing, and it is the right kind of thing.
     *
     * <p><b>Deliberately no free-cover-limit rule here.</b> An earlier draft refused a FREEFORM
     * life whose benefit exceeded the limit, reasoning that the excess needs evidence, evidence
     * needs an underwriting case, and a case needs a registered person. That rule protected
     * nothing and would have done real harm.
     *
     * <p>It protected nothing because {@code PolicyMember.referForEvidence} has no caller: NO
     * above-FCL member on this platform gets a case opened, registered or not. A freeform life
     * would have been refused for lacking something a party member never receives either.
     *
     * <p>It would have done harm because a member is a dependent record on the policy in the
     * way a beneficiary is, and very often a literal dependant — a spouse, a child. A child has
     * no KYC to produce and never will. Refusing them would withhold cover the employer has
     * already bought, over paperwork that does not exist for that person.
     *
     * <p>What happens above the limit is unchanged and was already right: the member is covered
     * AT the limit with the excess ungranted — the figure a claim pays — and the row reads
     * EVIDENCE_REQUIRED so the desk can see it.
     */
    private void validateDesignation(MemberInput input) {
        if (input.memberType() == null) {
            throw new InvalidPolicyStateException("A member must say whether it names a registered person or a name");
        }
        if (input.memberType() == MemberType.PARTY) {
            if (input.memberPartyId() == null || input.memberName() != null) {
                throw new InvalidPolicyStateException(
                    "A PARTY member must name a registered person and must not carry a name of its own");
            }
            partyApi.getParty(input.memberPartyId());
            return;
        }
        if (input.memberName() == null || input.memberName().isBlank() || input.memberPartyId() != null) {
            throw new InvalidPolicyStateException(
                "A FREEFORM member must carry a name and must not name a registered person");
        }
    }
```

Call it from `issueGroupScheme` (inside the loop that persists the opening schedule) and from `replaceMember` in Task 4. It reads only the input, so it can run before valuation. Delete the old `partyApi.getParty(member.memberPartyId())` and `if (member.memberPartyId() == null)` checks — `validateDesignation` subsumes both.

Update `persistMember` to pass the new fields:

```java
        PolicyMember member = policyMemberRepository.save(new PolicyMember(tenantId, policyNumber,
            input.memberType(), input.memberPartyId(), input.memberName(), input.memberDateOfBirth(),
            input.gradeCode(), joinedOn, valuation.underwritingStatus(), createdBy));
```

and both `toMemberView` overloads to pass `m.getMemberType()` and `m.getMemberName()`.

Add the shared name resolver Task 5 needs:

```java
    /**
     * The name to put in front of a human -- an event payload, a message, a log line.
     *
     * <p>A FREEFORM member carries its own; a PARTY member's is fetched, because copying it onto
     * the member row is how the schedule and the register end up disagreeing about who is
     * insured after somebody corrects a spelling.
     */
    private String displayNameOf(PolicyMember member) {
        return member.getMemberType() == MemberType.FREEFORM
            ? member.getMemberName()
            : partyApi.getParty(member.getMemberPartyId()).displayName();
    }
```

- [ ] **Step 9: Make the name search find freeform members**

In `PolicyMemberRepository.java`, replace the `findMembers` query:

```java
    /**
     * As above, with a null {@code status} meaning "every member, including those who left".
     *
     * <p>The name search is now TWO searches OR'd together, and it has to be. A PARTY member
     * holds no name -- the party module resolves {@code memberPartyIds} for those -- while a
     * FREEFORM member holds nothing else. Filtering on ids alone made every freeform life
     * unfindable on the one screen built to answer "is this person covered".
     *
     * <p>Both parameters are null for "no name filter". {@code memberPartyIds} may now be EMPTY
     * rather than short-circuited by the caller: an empty id set no longer means "no member
     * matches", because a freeform name may still match. The query uses {@code is null or ...
     * or lower(...) like ...} so an empty collection contributes nothing instead of erroring.
     */
    @Query("""
        select m from PolicyMember m
         where m.tenantId = :tenantId and m.policyNumber = :policyNumber
           and (:status is null or m.status = :status)
           and (:nameQuery is null
                or m.memberPartyId in :memberPartyIds
                or lower(m.memberName) like lower(concat('%', :nameQuery, '%')))
        """)
    Page<PolicyMember> findMembers(@Param("tenantId") UUID tenantId,
                                    @Param("policyNumber") String policyNumber,
                                    @Param("status") String status,
                                    @Param("nameQuery") String nameQuery,
                                    @Param("memberPartyIds") Collection<UUID> memberPartyIds,
                                    Pageable pageable);
```

In `PolicyApiImpl.listMembers`, delete the `if (nameMatches.isEmpty()) return Page.empty(pageable);` short-circuit and pass both parameters. An empty id set must still reach the query, so substitute a single impossible id rather than an empty `IN` list, which Postgres rejects:

```java
        String nameQuery = (q != null && !q.isBlank()) ? q.trim() : null;
        // A never-matching id rather than an empty collection: `in ()` is a Postgres syntax
        // error, and null would mean "no filter" and return the whole roll for a search that
        // matched nobody. The freeform half of the OR is what makes the search still work when
        // no party matched at all.
        Collection<UUID> nameMatches = nameQuery == null
            ? List.of(new UUID(0L, 0L))
            : orNeverMatches(partyApi.partyIdsMatchingName(nameQuery));
```

```java
    private static Collection<UUID> orNeverMatches(Set<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0L, 0L)) : ids;
    }
```

- [ ] **Step 10: Follow the contract**

In `backend/api/openapi/openapi-policy.yaml`, add to `GroupMemberInput`:

```yaml
        memberType:
          description: >-
            PARTY names a registered person; FREEFORM carries the life's own name. Exactly one
            of memberPartyId / memberName goes with each, and the wrong pairing is a 409 naming
            the designation. FREEFORM exists because a scheme covers dependants — a spouse, a
            child — who have no KYC to produce, and because a 500-life schedule should not put
            500 rows into a KYC review queue. It carries no further restriction: a FREEFORM life
            above the scheme's free cover limit is admitted and covered to the limit, exactly as
            a registered one is.
          type: string
          enum: [PARTY, FREEFORM]
        memberName: { type: [string, "null"], maxLength: 255 }
        memberDateOfBirth: { type: [string, "null"], format: date }
```

and change `required: [memberPartyId]` to `required: [memberType]`. Add to `PolicyMemberView`:

```yaml
        memberType: { type: string, enum: [PARTY, FREEFORM] }
        memberName:
          description: >-
            The life's own name on a FREEFORM member. NULL on a PARTY member, whose name lives
            in the party module — null here means "ask party", never "nameless".
          type: [string, "null"]
```

and make `memberPartyId` nullable. Mirror all of it in `GroupMemberInputDto.java` and `PolicyMemberResponseDto.java`.

- [ ] **Step 11: Fix the 26 existing `MemberInput` call sites**

All 26 are in `GroupSchemeIntegrationTest.java`. Each becomes `new PolicyApi.MemberInput(MemberType.PARTY, <existingPartyId>, null, null, <grade>, <salary>, <joinedOn>)`.

- [ ] **Step 12: Compile clean, run the group tests, then the module**

```
cd backend && ./mvnw -o clean test-compile && ./mvnw -o test -Dtest='GroupSchemeIntegrationTest+PolicyContractTest+PolicyApiIntegrationTest' -DfailIfNoTests=false
```

Expected: BUILD SUCCESS, the four new tests passing.

- [ ] **Step 13: Prove the FCL refusal is falsifiable**

Temporarily change `theRollIsSearchableByAFreeformNameAsWellAsARegisteredOne`'s final assertion from `.isEmpty()` to `.hasSize(2)` and confirm it FAILS — the "matched nobody" branch is where the old empty-`IN` short-circuit lived, and where a wrong fix silently returns the entire schedule. Restore it. A branch nobody has watched fail is a branch nobody has tested.

- [ ] **Step 14: Apply to the dev database and commit**

```bash
cd backend
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/policy/V13__freeform_members.sql
git add -A backend/db-migrations/policy backend/src backend/api/openapi/openapi-policy.yaml
git commit -m "feat(policy): let a scheme name a life without registering them as a party"
```

---

## Task 4: Substitution replaces addition

The scheme is a fixed-headcount contract. `POST /group-schemes/{n}/members` is removed, not deprecated: leaving an add endpoint on a contract that cannot grow is an invitation to write an unpriced life onto it.

**Files:**
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `policy/infrastructure/PolicyController.java`, `backend/api/openapi/openapi-policy.yaml`, `backend/api/asyncapi-events.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/GroupSchemeIntegrationTest.java`

**Interfaces:**
- Consumes: `MemberType`, the widened `MemberInput`, `validateDesignation`, `displayNameOf` from Task 3; `PolicyMember.exit(LocalDate)` (already on the entity, and until now called by nothing); `restateSchemeTotal(Policy, UUID, LocalDate)`.
- Produces, for Tasks 5–7:
  - `PolicyMemberView replaceMember(String policyNumber, UUID outgoingPolicyMemberId, MemberInput incoming, LocalDate effectiveFrom, String replacedBy)`
  - `POST /group-schemes/{policyNumber}/members/{policyMemberId}/replacement`
  - event `policy.GroupMemberReplaced` with payload keys `policyNumber`, `policyholderPartyId`, `outgoingMemberName`, `incomingMemberName`, `effectiveFrom`, `schemeTotalCovered` (a `{amount, currencyCode}` map)

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void substitutingALifeKeepsTheHeadcountAndSwapsTheCover() {
        TenantContext.set(UUID.randomUUID());
        GroupSchemeView scheme = twoLifeScheme("GRP-SWAP-01", "Juma Leaver", "Asha Stayer");
        PolicyMemberView leaver = memberNamed(scheme.policyNumber(), "Juma Leaver");

        LocalDate swapDate = LocalDate.now();
        PolicyMemberView joiner = policyApi.replaceMember(scheme.policyNumber(),
            leaver.policyMemberId(),
            new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Neema Joiner", null, null, null, null),
            swapDate, "staff1");

        assertThat(joiner.memberName()).isEqualTo("Neema Joiner");
        assertThat(joiner.joinedOn()).isEqualTo(swapDate);

        Page<PolicyMemberView> active = policyApi.listMembers(scheme.policyNumber(),
            MemberStatus.ACTIVE, null, PageRequest.of(0, 25));
        assertThat(active.getTotalElements())
            .as("one out, one in -- a scheme's headcount is fixed for the period")
            .isEqualTo(2);
        assertThat(active.getContent()).extracting(PolicyMemberView::memberName)
            .containsExactlyInAnyOrder("Asha Stayer", "Neema Joiner");

        PolicyMemberView exited = memberNamed(scheme.policyNumber(), "Juma Leaver");
        assertThat(exited.status()).isEqualTo(MemberStatus.EXITED);
        assertThat(exited.leftOn())
            .as("the row is kept: a claim can arrive after somebody leaves")
            .isEqualTo(swapDate);
    }

    @Test
    void substitutionDoesNotChangeThePremium() {
        // The premium is agreed for the period. A scheme that re-rated on every staff change
        // would send the employer a different bill every month for the same 500 lives.
        TenantContext.set(UUID.randomUUID());
        GroupSchemeView scheme = twoLifeScheme("GRP-SWAP-PREMIUM", "Juma Leaver", "Asha Stayer");
        BigDecimal premiumBefore = policyApi.getPolicy(scheme.policyNumber()).premiumAmount();

        policyApi.replaceMember(scheme.policyNumber(),
            memberNamed(scheme.policyNumber(), "Juma Leaver").policyMemberId(),
            new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Neema Joiner", null, null, null, null),
            LocalDate.now(), "staff1");

        assertThat(policyApi.getPolicy(scheme.policyNumber()).premiumAmount())
            .isEqualByComparingTo(premiumBefore);
    }

    @Test
    void aSchemeCannotBeGrownAfterIssuance() {
        // The endpoint is gone; this pins the API surface so it cannot come back by accident.
        assertThat(java.util.Arrays.stream(PolicyApi.class.getMethods()).map(java.lang.reflect.Method::getName))
            .as("a fixed-headcount contract has no add")
            .doesNotContain("addMember");
    }

    @Test
    void onlyAnActiveMemberOfThisSchemeCanBeReplaced() {
        TenantContext.set(UUID.randomUUID());
        GroupSchemeView scheme = twoLifeScheme("GRP-SWAP-GUARD", "Juma Leaver", "Asha Stayer");
        UUID leaverId = memberNamed(scheme.policyNumber(), "Juma Leaver").policyMemberId();

        policyApi.replaceMember(scheme.policyNumber(), leaverId,
            new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Neema Joiner", null, null, null, null),
            LocalDate.now(), "staff1");

        assertThatThrownBy(() -> policyApi.replaceMember(scheme.policyNumber(), leaverId,
            new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Second Joiner", null, null, null, null),
            LocalDate.now(), "staff1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("is not an active member");
    }

    @Test
    void aSubstitutionCannotBeDatedIntoTheFutureOrBeforeTheLifeItReplacesJoined() {
        TenantContext.set(UUID.randomUUID());
        GroupSchemeView scheme = twoLifeScheme("GRP-SWAP-DATES", "Juma Leaver", "Asha Stayer");
        UUID leaverId = memberNamed(scheme.policyNumber(), "Juma Leaver").policyMemberId();
        PolicyApi.MemberInput joiner =
            new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Neema Joiner", null, null, null, null);

        assertThatThrownBy(() -> policyApi.replaceMember(scheme.policyNumber(), leaverId, joiner,
            LocalDate.now().plusDays(1), "staff1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("future");

        assertThatThrownBy(() -> policyApi.replaceMember(scheme.policyNumber(), leaverId, joiner,
            scheme.commencementDate().minusDays(1), "staff1"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("before");
    }
```

Add the two helpers used above to the same test class, beside the existing `groupProduct` / `person` / `flatScheme` fixtures:

```java
    /**
     * A flat scheme carrying two named lives at 5,000,000 each, commenced today.
     *
     * <p>Both lives are FREEFORM, which keeps the substitution tests about substitution: a
     * PARTY member would drag party registration and the active-membership index into every
     * assertion below, and neither is what is under test here.
     */
    private GroupSchemeView twoLifeScheme(String productCode, String firstName, String secondName) {
        GroupProduct product = groupProduct(productCode);
        UUID employer = person("ABC Company");
        return policyApi.issueGroupScheme(
            flatScheme(product, employer, new BigDecimal("5000000.00"), null,
                List.of(new PolicyApi.MemberInput(MemberType.FREEFORM, null, firstName, null, null, null, null),
                        new PolicyApi.MemberInput(MemberType.FREEFORM, null, secondName, null, null, null, null))),
            "staff1");
    }

    /** The member with this name, active or exited. Fails loudly rather than returning null. */
    private PolicyMemberView memberNamed(String policyNumber, String name) {
        return policyApi.listMembers(policyNumber, null, name, PageRequest.of(0, 25))
            .getContent().stream().findFirst()
            .orElseThrow(() -> new AssertionError("No member named " + name + " on " + policyNumber));
    }
```

- [ ] **Step 2: Run them and watch them fail**

```
cd backend && ./mvnw -o test -Dtest='GroupSchemeIntegrationTest#substitutingALifeKeepsTheHeadcountAndSwapsTheCover' -DfailIfNoTests=false
```

Expected: FAIL to compile — `replaceMember` does not exist.

- [ ] **Step 3: Replace `addMember` on the API**

In `policy/api/PolicyApi.java`, delete the `addMember` declaration and its javadoc and put in its place:

```java
    /**
     * Substitute one life for another: the outgoing member leaves, the incoming one joins, on
     * the same date and in the same transaction.
     *
     * <p><b>A scheme's headcount is fixed at issuance, which is why there is no add.</b> The
     * employer bought cover for a stated number of lives and was priced for it; admitting a
     * 501st life mid-year would raise the sum assured against a premium agreed for 500. Staff
     * turnover is the case this exists for — Juma leaves, Neema takes his place, and the
     * contract is unchanged in every respect that was priced.
     *
     * <p>The premium is NOT re-rated. The scheme's sum assured may still move, because it is by
     * definition the sum of the schedule and the substitute may sit on a different grade or
     * salary. That is the contract total tracking its own members, not a repricing.
     *
     * <p>Substitution is also the only way a member leaves. A standalone exit is deliberately
     * not modelled: on a fixed-headcount scheme, leaving without a replacement would drop the
     * cover the employer is paying for and nobody would be told which.
     *
     * @param outgoingPolicyMemberId the member to retire. Must be ACTIVE on this scheme.
     * @param effectiveFrom the swap date: the outgoing member's last day of cover and the
     *     incoming member's first. Backdating is allowed and normal — a schedule reaches the
     *     insurer weeks after the change. A future date is refused, for the same reason
     *     {@link #issueGroupScheme} refuses a future commencement.
     * @throws InvalidPolicyStateException if the policy is not in force, the outgoing member is
     *     not an active member of this scheme, the incoming input does not match the scheme's
     *     basis or designation rules, or the date is in the future, before the scheme
     *     commenced, or before the outgoing member joined
     */
    PolicyMemberView replaceMember(String policyNumber, UUID outgoingPolicyMemberId,
                                    MemberInput incoming, LocalDate effectiveFrom, String replacedBy);
```

- [ ] **Step 4: Implement it**

In `PolicyApiImpl.java`, delete `addMember` entirely and add:

```java
    @Override
    @Transactional
    public PolicyMemberView replaceMember(String policyNumber, UUID outgoingPolicyMemberId,
                                           MemberInput incoming, LocalDate effectiveFrom, String replacedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        GroupScheme scheme = findSchemeOrThrow(policyNumber, tenantId);
        if (!policy.isInForce()) {
            throw new InvalidPolicyStateException("Scheme " + policyNumber
                + " must be in force to substitute a member (current: " + policy.getStatus() + ")");
        }

        PolicyMember outgoing = policyMemberRepository
            .findByPolicyMemberIdAndTenantId(outgoingPolicyMemberId, tenantId)
            .filter(m -> m.getPolicyNumber().equals(policyNumber))
            .filter(m -> MemberStatus.ACTIVE.name().equals(m.getStatus()))
            // One message for "no such member", "member of another scheme" and "already left".
            // A caller holding a stale member id must not be able to tell those apart by the
            // error text -- it would enumerate other tenants' schedules a row at a time.
            .orElseThrow(() -> new InvalidPolicyStateException("Member " + outgoingPolicyMemberId
                + " is not an active member of scheme " + policyNumber));

        LocalDate today = LocalDate.now();
        if (effectiveFrom == null) {
            throw new InvalidPolicyStateException("A substitution must say what date it takes effect");
        }
        if (effectiveFrom.isAfter(today)) {
            throw new InvalidPolicyStateException(
                "A substitution cannot be dated into the future; record it on the day the change took effect");
        }
        if (policy.getCommencementDate() != null && effectiveFrom.isBefore(policy.getCommencementDate())) {
            throw new InvalidPolicyStateException("A substitution cannot take effect before the scheme commenced on "
                + policy.getCommencementDate());
        }
        if (effectiveFrom.isBefore(outgoing.getJoinedOn())) {
            throw new InvalidPolicyStateException("A substitution cannot take effect before "
                + outgoing.getJoinedOn() + ", when the member being replaced joined");
        }

        GroupBenefitCalculator.Valuation valuation = valueMember(scheme.getBenefitBasis(),
            scheme.getFlatBenefitAmount(), scheme.getSalaryMultiple(), scheme.getFclAmount(),
            gradeTableFor(tenantId, policyNumber, scheme.getBenefitBasis()), incoming);
        validateDesignation(incoming);

        if (incoming.memberType() == MemberType.PARTY
                && policyMemberRepository.existsByTenantIdAndPolicyNumberAndMemberPartyIdAndStatus(
                    tenantId, policyNumber, incoming.memberPartyId(), MemberStatus.ACTIVE.name())) {
            throw new InvalidPolicyStateException("That person is already an active member of scheme " + policyNumber);
        }

        // Names resolved BEFORE the exit, while both rows are still readable as members, and
        // captured for the event: communication cannot depend on policy, so a message that
        // names the two lives can only do it from the payload.
        String outgoingName = displayNameOf(outgoing);

        outgoing.exit(effectiveFrom);
        policyMemberRepository.save(outgoing);
        PolicyMember joined = persistMember(tenantId, policyNumber, incoming, valuation, effectiveFrom, replacedBy);

        // Flush before restating, so the total sees the exit and the arrival together. Both in
        // one transaction: a scheme must never be readable with 499 lives.
        policyMemberBenefitRepository.flush();
        BigDecimal total = restateSchemeTotal(policy, tenantId, today);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        // The employer, carried in the payload because communication resolves the recipient
        // from a party id and has no way to look one up from a policy number.
        payload.put("policyholderPartyId", policy.getPolicyholderPartyId());
        payload.put("outgoingMemberName", outgoingName);
        payload.put("incomingMemberName", displayNameOf(joined));
        payload.put("effectiveFrom", effectiveFrom.toString());
        payload.put("schemeTotalCovered", Map.of("amount", total.toPlainString(),
            "currencyCode", scheme.getCurrency()));
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.GroupMemberReplaced", tenantId, payload));

        return toMemberView(joined, valuation, incoming.salaryAmount(), scheme.getCurrency(), effectiveFrom);
    }
```

Also add `policyholderPartyId` to the `GroupSchemeIssued` payload in `issueGroupScheme` — Task 5 needs it, and a scheme event that cannot name its own employer is incomplete regardless:

```java
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.GroupSchemeIssued", tenantId, Map.of(
            "policyNumber", policyNumber,
            "policyholderPartyId", request.policyholderPartyId(),
            "benefitBasis", request.benefitBasis().name(),
            "memberCount", valued.size(),
            "totalCovered", Map.of("amount", total.toPlainString(), "currencyCode", request.currency()))));
```

- [ ] **Step 5: Swap the endpoint**

In `PolicyController.java`, delete the `addMember` handler and add:

```java
    /**
     * Substitute one life for another. There is no add: a scheme's headcount is fixed at
     * issuance, so the only movement is a swap.
     */
    @PostMapping("/group-schemes/{policyNumber}/members/{policyMemberId}/replacement")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyMemberResponseDto> replaceMember(@PathVariable String policyNumber,
            @PathVariable UUID policyMemberId, @Valid @RequestBody ReplaceMemberRequestDto request,
            @AuthenticationPrincipal Jwt jwt) {
        PolicyMemberView view = policyApi.replaceMember(policyNumber, policyMemberId,
            request.incoming().toApiInput(), request.effectiveFrom(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyMemberResponseDto.from(view));
    }
```

Create `policy/infrastructure/ReplaceMemberRequestDto.java`:

```java
package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Mirrors openapi-policy.yaml's ReplaceMemberRequest.
 *
 * <p>{@code effectiveFrom} is required rather than defaulted to today. A substitution is a
 * contractual date — the outgoing life's last day of cover — and a schedule that reaches the
 * insurer three weeks late must not silently record the change as having happened on the day
 * somebody typed it.
 */
public record ReplaceMemberRequestDto(@NotNull @Valid GroupMemberInputDto incoming,
                                       @NotNull LocalDate effectiveFrom) {}
```

- [ ] **Step 6: Follow the contract**

In `openapi-policy.yaml`, delete the `post` operation under `/group-schemes/{policyNumber}/members`, add the `/group-schemes/{policyNumber}/members/{policyMemberId}/replacement` path with a `ReplaceMemberRequest` schema (`incoming: GroupMemberInput`, `effectiveFrom: date`, both required), returning `201` with `PolicyMemberView` and `409` for every `InvalidPolicyStateException` above.

In `backend/api/asyncapi-events.yaml`, add both `policy.GroupSchemeIssued` (keys `policyNumber`, `policyholderPartyId`, `benefitBasis`, `memberCount`, `totalCovered`) and `policy.GroupMemberReplaced` (keys as in **Produces** above). Neither event is catalogued today, which is its own defect: an event with no entry is an event no consumer can be written against.

- [ ] **Step 7: Compile clean and run the group tests**

```
cd backend && ./mvnw -o clean test-compile && ./mvnw -o test -Dtest='GroupSchemeIntegrationTest+PolicyContractTest' -DfailIfNoTests=false
```

Expected: BUILD SUCCESS, all five new tests passing.

- [ ] **Step 8: Commit**

```bash
git add -A backend/src backend/api
git commit -m "feat(policy): substitute a scheme's lives instead of adding to them"
```

---

## Task 5: Tell the employer

Two messages, employer only. Members are never messaged — the employer buys the cover and is the party with a relationship to this tenant.

**Files:**
- Create: `backend/db-migrations/communication/V10__group_scheme_templates.sql`, `communication/application/GroupSchemeEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/communication/GroupSchemeNotificationTest.java`

**Interfaces:**
- Consumes: `NotificationApi.notify(UUID eventId, UUID partyId, String policyNumber, String templateKey, Map<String,String> values)`; the `policy.GroupSchemeIssued` and `policy.GroupMemberReplaced` payloads from Task 4.
- Produces: template keys `GROUP_SCHEME_LIVE` (placeholders `policyNumber`, `memberCount`, `totalCovered`) and `GROUP_MEMBER_REPLACED` (placeholders `policyNumber`, `outgoingName`, `incomingName`, `effectiveFrom`).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/communication/GroupSchemeNotificationTest.java` by copying the whole setup block of `OfferNotificationEndToEndTest` verbatim — the `@Testcontainers` / `@SpringBootTest(classes = Application.class, properties = "communication.sms-gateway.live=true")` annotations, the `POSTGRES` container, the `smsGateway` WireMock server, `SEEDED_TENANT`, `properties(...)`, `startEverything()`, `stopGateway()`, the `@BeforeEach acceptEverySms()` stub and the `@AfterEach clearTenant()`. To its migration list add `db-migrations/policy/V9__group_scheme_and_members.sql`, `db-migrations/policy/V13__freeform_members.sql` and `db-migrations/communication/V10__group_scheme_templates.sql`. Autowire `PartyApi`, `ProductApi`, `PolicyApi`, `NotificationApi` and `NotificationDispatchRepository`.

**There is no rendered-body column and no `renderedBody()` accessor.** `NotificationDispatchView` is `(dispatchId, partyId, policyNumber, templateKey, channel, status, failureReason, dispatchedAt, createdAt)`. The message text is asserted the way `OfferNotificationEndToEndTest` already does it — read off the WireMock request, which is the only place the customer's actual words exist.

```java
    /** The scheme fixtures, copied from GroupSchemeIntegrationTest -- same shapes, same reasons. */
    private record GroupProduct(UUID productId, UUID productVersionId) {}
    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(4000);

    private GroupProduct groupProduct(String code) {
        ProductSummaryView product = productApi.createProduct(code, "Group Life " + code,
            ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        return new GroupProduct(product.productId(),
            productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
    }

    /**
     * An employer who can actually be reached. A party with no phone and no email produces a
     * FAILED dispatch, which would make every assertion below pass for the wrong reason.
     */
    private UUID employer(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", PHONE_SEQ.incrementAndGet()), null, "test-agent").partyId();
    }

    private GroupSchemeView twoLifeScheme(String productCode, UUID employerPartyId) {
        GroupProduct product = groupProduct(productCode);
        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employerPartyId, product.productId(), product.productVersionId(), null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Juma Leaver", null, null, null, null),
                    new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Asha Stayer", null, null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null, "group onboarding"),
            "staff1");
    }

    private List<NotificationDispatch> messagesAbout(String policyNumber) {
        TenantContext.set(SEEDED_TENANT);
        return dispatchRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtDesc(SEEDED_TENANT, policyNumber);
    }

    @Test
    void theEmployerIsToldTheSchemeIsLiveAndTheLivesAreNot() {
        TenantContext.set(SEEDED_TENANT);
        UUID employerPartyId = employer("ABC Company");
        GroupSchemeView scheme = twoLifeScheme("GRP-NOTIFY-01", employerPartyId);

        assertThat(messagesAbout(scheme.policyNumber()))
            .singleElement()
            .satisfies(dispatch -> {
                assertThat(dispatch.getTemplateKey()).isEqualTo("GROUP_SCHEME_LIVE");
                // SENT, not merely recorded: notify marks a dispatch FAILED when the template
                // declares a placeholder nothing supplied, so SENT is what proves the listener's
                // value map actually satisfies the message.
                assertThat(dispatch.getStatus()).isEqualTo("SENT");
                assertThat(dispatch.getPartyId())
                    .as("the employer buys the cover; the lives on the schedule are never messaged")
                    .isEqualTo(employerPartyId);
            });

        // The body actually sent, read off the wire. The dispatch row proves an attempt was
        // recorded; only the request proves the employer was told the right thing.
        String sent = smsGateway.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(sent).contains(scheme.policyNumber());
        assertThat(sent).contains("2");
        assertThat(sent).contains("TZS 10,000,000.00");
    }

    @Test
    void theEmployerIsToldWhenTheScheduleChanges() {
        TenantContext.set(SEEDED_TENANT);
        UUID employerPartyId = employer("ABC Company");
        GroupSchemeView scheme = twoLifeScheme("GRP-NOTIFY-02", employerPartyId);
        UUID leaverId = policyApi.listMembers(scheme.policyNumber(), null, "Juma Leaver",
            PageRequest.of(0, 25)).getContent().get(0).policyMemberId();
        smsGateway.resetRequests();

        TenantContext.set(SEEDED_TENANT);
        policyApi.replaceMember(scheme.policyNumber(), leaverId,
            new PolicyApi.MemberInput(MemberType.FREEFORM, null, "Neema Joiner", null, null, null, null),
            LocalDate.now(), "staff1");

        assertThat(messagesAbout(scheme.policyNumber()))
            .filteredOn(d -> "GROUP_MEMBER_REPLACED".equals(d.getTemplateKey()))
            .singleElement()
            .satisfies(dispatch -> {
                assertThat(dispatch.getStatus()).isEqualTo("SENT");
                assertThat(dispatch.getPartyId()).isEqualTo(employerPartyId);
            });

        String sent = smsGateway.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(sent)
            .as("an HR officer needs both names in writing, not a count that moved")
            .contains("Juma Leaver")
            .contains("Neema Joiner");
    }

    @Test
    void aRedeliveredSchemeEventSendsNothingASecondTime() {
        // notify is idempotent on eventId and this is where it earns its keep: a second SMS is
        // a second SMS on the employer's phone, and a customer told twice learns that this
        // platform's messages are noise.
        TenantContext.set(SEEDED_TENANT);
        UUID employerPartyId = employer("ABC Company");
        GroupSchemeView scheme = twoLifeScheme("GRP-NOTIFY-03", employerPartyId);
        int before = messagesAbout(scheme.policyNumber()).size();

        UUID eventId = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            TenantContext.set(SEEDED_TENANT);
            notificationApi.notify(eventId, employerPartyId, scheme.policyNumber(), "GROUP_SCHEME_LIVE",
                Map.of("policyNumber", scheme.policyNumber(), "memberCount", "2",
                    "totalCovered", "TZS 10,000,000.00"));
        }

        assertThat(messagesAbout(scheme.policyNumber())).hasSize(before + 1);
    }
```

- [ ] **Step 2: Run it and watch it fail**

```
cd backend && ./mvnw -o test -Dtest='GroupSchemeNotificationTest' -DfailIfNoTests=false
```

Expected: FAIL — no dispatch rows; nothing consumes the group events.

- [ ] **Step 3: Seed the templates**

`backend/db-migrations/communication/V10__group_scheme_templates.sql`:

```sql
-- db-migrations/communication/V10__group_scheme_templates.sql
-- Two messages for the employer, and none for the lives.
--
-- A group scheme has never sent anybody anything. The four offer-lifecycle messages are gated
-- on an offer -- offerMade returns early unless status is PROPOSED -- and a scheme goes on risk
-- at issuance and never publishes PolicyActivated, so all of them no-op. An employer bought
-- cover for their whole staff and the platform said nothing at all.
--
-- ONLY THE EMPLOYER. The members are the insured lives, but the employer is the customer: they
-- chose the cover, they pay for it, and they are the party this tenant has a relationship with.
-- Messaging 500 employees is a different product decision with a different consent question
-- behind it, and it is deliberately not this one.
--
-- Deliberately no "premium due" or "premium overdue" here. Neither exists for individual
-- policies either, and dunning needs a staff surface that approves automatic sending before
-- anything starts leaving on a schedule. Adding a due-date template now would put the message
-- in place and leave the approval out.
INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES

('00000000-0000-0000-0000-000000000000', 'GROUP_SCHEME_LIVE', 'SMS', 'sw',
 'Mpango wa bima {{policyNumber}} umeanza. Wanachama {{memberCount}}, jumla ya bima {{totalCovered}}.'),
('00000000-0000-0000-0000-000000000000', 'GROUP_SCHEME_LIVE', 'SMS', 'en',
 'Group scheme {{policyNumber}} is now live. {{memberCount}} members, {{totalCovered}} total cover.'),
('00000000-0000-0000-0000-000000000000', 'GROUP_SCHEME_LIVE', 'EMAIL', 'sw',
 'Mpango wa bima {{policyNumber}} umeanza. Wanachama {{memberCount}}, jumla ya bima {{totalCovered}}. Hifadhi ujumbe huu kama uthibitisho.'),
('00000000-0000-0000-0000-000000000000', 'GROUP_SCHEME_LIVE', 'EMAIL', 'en',
 'Group scheme {{policyNumber}} is now live. {{memberCount}} members, {{totalCovered}} total cover. Please keep this message as confirmation.'),

('00000000-0000-0000-0000-000000000000', 'GROUP_MEMBER_REPLACED', 'SMS', 'sw',
 'Bima {{policyNumber}}: {{outgoingName}} ameondolewa na {{incomingName}} ameingizwa tarehe {{effectiveFrom}}.'),
('00000000-0000-0000-0000-000000000000', 'GROUP_MEMBER_REPLACED', 'SMS', 'en',
 'Scheme {{policyNumber}}: {{outgoingName}} was replaced by {{incomingName}} on {{effectiveFrom}}.'),
('00000000-0000-0000-0000-000000000000', 'GROUP_MEMBER_REPLACED', 'EMAIL', 'sw',
 'Bima {{policyNumber}}: {{outgoingName}} ameondolewa na {{incomingName}} ameingizwa tarehe {{effectiveFrom}}. Idadi ya wanachama haijabadilika.'),
('00000000-0000-0000-0000-000000000000', 'GROUP_MEMBER_REPLACED', 'EMAIL', 'en',
 'Scheme {{policyNumber}}: {{outgoingName}} was replaced by {{incomingName}} on {{effectiveFrom}}. The member count is unchanged.');
```

- [ ] **Step 4: Add the migration to the test classes that list communication migrations**

```bash
cd backend && node ../scratchpad/add-comm-v10.js $(grep -rl "db-migrations/communication/V9__payment_received_template.sql" src/test/java)
```

(Same script as Task 2, with `AFTER = 'db-migrations/communication/V9__payment_received_template.sql'` and `NEW = 'db-migrations/communication/V10__group_scheme_templates.sql'`.)

- [ ] **Step 5: Write the listener**

`backend/src/main/java/tz/co/nlolo/lifeplatform/communication/application/GroupSchemeEventListener.java`:

```java
package tz.co.nlolo.lifeplatform.communication.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tells an employer about their scheme.
 *
 * <p>Its own listener rather than two more branches inside {@code PolicyEventListener}, because
 * the audience is different and that difference is the whole design. The offer messages speak to
 * a person about their own cover; these speak to a company about a contract covering other
 * people. Folding them together would put the two one careless edit apart.
 *
 * <p><b>The employer, and nobody else.</b> Members are the insured lives and are never messaged
 * here — the employer chose the cover and pays for it. Messaging staff directly is a separate
 * product decision with a consent question behind it.
 *
 * <p>{@code AFTER_COMMIT} with {@code PROPAGATION_REQUIRES_NEW}, for the reason
 * {@code policy.application.UnderwritingDecisionEventListener} documents: at AFTER_COMMIT the
 * producer's transaction has committed but Spring has not unbound its resources, so a plain
 * REQUIRED call silently joins an already-committed transaction and the writes never land.
 *
 * <p>Everything the message needs arrives in the payload. This module may depend on
 * {@code party::api} and {@code refdata::api} only, so it cannot look a scheme up — which is
 * why {@code GroupMemberReplaced} carries both members' NAMES rather than their ids.
 *
 * <p>Bean name is explicit: four other modules already declare a {@code PolicyEventListener} and
 * this package declares two listeners of its own; an unqualified name is a startup collision.
 */
@Component("communicationGroupSchemeEventListener")
public class GroupSchemeEventListener {

    private static final Logger log = LoggerFactory.getLogger(GroupSchemeEventListener.class);

    private final NotificationApi notificationApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public GroupSchemeEventListener(NotificationApi notificationApi,
                                     PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String templateKey = switch (envelope.eventType()) {
            case "policy.GroupSchemeIssued" -> "GROUP_SCHEME_LIVE";
            case "policy.GroupMemberReplaced" -> "GROUP_MEMBER_REPLACED";
            default -> null;
        };
        if (templateKey == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();

        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            Map<String, String> values = "GROUP_SCHEME_LIVE".equals(templateKey)
                ? Map.of(
                    "policyNumber", (String) payload.get("policyNumber"),
                    "memberCount", String.valueOf(payload.get("memberCount")),
                    "totalCovered", money(payload.get("totalCovered")))
                : Map.of(
                    "policyNumber", (String) payload.get("policyNumber"),
                    "outgoingName", (String) payload.get("outgoingMemberName"),
                    "incomingName", (String) payload.get("incomingMemberName"),
                    "effectiveFrom", (String) payload.get("effectiveFrom"));

            requiresNewTransactionTemplate.executeWithoutResult(status ->
                notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
                    (String) payload.get("policyNumber"), templateKey, values));
        } catch (Exception e) {
            // The scheme is issued and the substitution is recorded either way. Rolling either
            // back to save a message would be exactly backwards; the dispatch row records the
            // failure for the desk.
            log.error("Failed to notify the employer about scheme {}", payload.get("policyNumber"), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /** "TZS 10,000,000.00" -- grouped, and Locale.ROOT so the message never depends on the sender's JVM. */
    private static String money(Object moneyPayload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) moneyPayload;
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return money.get("currencyCode") + " " + format.format(new BigDecimal((String) money.get("amount")));
    }
}
```

- [ ] **Step 6: Run the tests**

```
cd backend && ./mvnw -o test -Dtest='GroupSchemeNotificationTest' -DfailIfNoTests=false
```

Expected: PASS, all three.

- [ ] **Step 7: Prove the employer-only rule is falsifiable**

Temporarily change `theEmployerIsToldTheSchemeIsLiveAndTheLivesAreNot` from `.singleElement()` to `.hasSize(3)` and confirm it FAILS — proving the test would actually catch a listener that messaged the two lives as well as the employer. Restore it. The assertion that nobody else is messaged is the one that must not be vacuous, and `singleElement()` only carries that meaning if it has been watched to fail.

- [ ] **Step 8: Apply to the dev database and commit**

```bash
cd backend
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/communication/V10__group_scheme_templates.sql
git add -A backend/db-migrations/communication backend/src
git commit -m "feat(communication): tell an employer their scheme is live and when its schedule changes"
```

---

## Task 6: The console follows

**Files:**
- Modify: `frontend/src/features/policies/GroupSchemePage.tsx`, `frontend/src/features/policies/addMemberForm.ts` → `substituteMemberForm.ts`, `frontend/src/features/policies/addMemberForm.test.ts` → `substituteMemberForm.test.ts`, `frontend/src/api/policies.ts`, `frontend/src/store/policyStore.ts`, `frontend/src/api/types.ts` (regenerated)
- Test: `frontend/src/features/policies/substituteMemberForm.test.ts`

**Interfaces:**
- Consumes: `POST /group-schemes/{policyNumber}/members/{policyMemberId}/replacement` and the widened `GroupMemberInput` / `PolicyMemberView` from Tasks 3–4.
- Produces: `substituteSchemeMember(policyNumber: string, policyMemberId: string, request: ReplaceMemberRequest)` on the policy store.

- [ ] **Step 1: Regenerate the API types**

```
cd frontend && npm run generate:types && npm run typecheck
```

Expected: typecheck FAILS at every site reading `memberPartyId` as non-nullable and at `addSchemeMember`. That failure list is the work.

- [ ] **Step 2: Write the failing form tests**

Rename `addMemberForm.test.ts` to `substituteMemberForm.test.ts` and add:

```ts
  it('requires a person when the member is designated PARTY', () => {
    expect(errorsFor({ benefitBasis: 'FLAT' }, values({ memberType: 'PARTY', memberPartyId: '' })))
      .toHaveProperty('memberPartyId');
  });

  it('requires a name when the member is designated FREEFORM, and no party', () => {
    const errors = errorsFor({ benefitBasis: 'FLAT' },
      values({ memberType: 'FREEFORM', memberPartyId: '', memberName: '' }));
    expect(errors).toHaveProperty('memberName');
  });

  it('accepts a freeform life with only a name', () => {
    expect(errorsFor({ benefitBasis: 'FLAT' },
      values({ memberType: 'FREEFORM', memberPartyId: '', memberName: 'Zawadi Mushi' })))
      .toEqual({});
  });

  it('requires an effective date, because a substitution is a contractual date', () => {
    // Not defaulted to today: a schedule that arrives three weeks late must not record the
    // change as having happened when somebody typed it.
    expect(errorsFor({ benefitBasis: 'FLAT' }, values({ effectiveFrom: '' })))
      .toHaveProperty('effectiveFrom');
  });

  it('refuses an effective date in the future', () => {
    expect(errorsFor({ benefitBasis: 'FLAT', today: '2026-09-10' },
      values({ effectiveFrom: '2026-09-11' }))).toHaveProperty('effectiveFrom');
  });
```

- [ ] **Step 3: Run them and watch them fail**

```
cd frontend && npx vitest run src/features/policies/substituteMemberForm.test.ts
```

Expected: FAIL — `memberType`, `memberName` and `effectiveFrom` are not on `MemberFormValues`.

- [ ] **Step 4: Rewrite the form module**

Rename `addMemberForm.ts` to `substituteMemberForm.ts`; `MemberFormValues` becomes:

```ts
export interface MemberFormValues {
  memberType: 'PARTY' | 'FREEFORM';
  memberPartyId: string;
  memberName: string;
  memberDateOfBirth: string;
  gradeCode: string;
  salaryAmount: string;
  effectiveFrom: string;
}
```

`memberPartyId` is required and UUID-shaped only when `memberType === 'PARTY'`; `memberName` required and non-blank only when `'FREEFORM'`; `effectiveFrom` required, ISO-shaped, not after `today`, not before `context.commencementDate`. `joinedOn` is gone — the substitution's `effectiveFrom` is the joiner's start date, and two dates that must always agree is one date with a bug waiting in it.

- [ ] **Step 5: Rewire the store and API client**

In `src/api/policies.ts`, replace `addSchemeMember` with:

```ts
/** `POST /group-schemes/{n}/members/{id}/replacement` -- one life out, one in, same instant. */
export function substituteSchemeMember(
  policyNumber: string,
  policyMemberId: string,
  request: ReplaceMemberRequest,
) {
  return post<PolicyMemberView>(
    `/group-schemes/${encodeURIComponent(policyNumber)}/members/${encodeURIComponent(policyMemberId)}/replacement`,
    request,
  );
}
```

and rename the `addingMember` store slice to `substitutingMember`, keyed by `policyMemberId` rather than `policyNumber` so two rows cannot share one spinner.

- [ ] **Step 6: Rework the page**

In `GroupSchemePage.tsx`, four changes.

**(a)** Delete the `addOpen` state, the "Add a member" toggle button and the `<Panel title="Add a member">` block. Replace the Members table's subtitle so nobody hunts for the button that used to be there:

```tsx
              <p className="text-xs text-muted-foreground">
                {/* Said here rather than discovered through a 409. A fixed headcount is the
                    contract, not a limitation of this screen. */}
                A fixed number of lives, each showing the benefit in force today. Replace a
                member to record someone leaving and someone taking their place.
              </p>
```

**(b)** Render a member's name from whichever source has it, and link only where there is somewhere to go — a freeform life has no party page:

```tsx
  function renderMemberName(m: PolicyMemberView) {
    // memberName is null on a PARTY member (their name lives in the party module) and set on
    // a FREEFORM one. Null therefore means "ask party", never "nameless".
    if (m.memberName) {
      return <span className="font-medium">{m.memberName}</span>;
    }
    return (
      <Link to={`/staff/parties/${m.memberPartyId}`} className="font-medium underline">
        <PartyName partyId={m.memberPartyId as string} />
      </Link>
    );
  }
```

**(c)** Add a Replace action to each ACTIVE row, and render the form for whichever row is open:

```tsx
  const [replacing, setReplacing] = useState<PolicyMemberView | null>(null);
```

```tsx
      m.status === 'ACTIVE' ? (
        <Button size="sm" variant="outline" onClick={() => setReplacing(m)}>
          Replace
        </Button>
      ) : null
```

```tsx
        {replacing && data && (
          <Panel
            title={`Replace ${replacing.memberName ?? 'this member'}`}
            subtitle="One life out, one in — the headcount and the premium do not move"
          >
            <SubstituteMemberForm
              scheme={data}
              outgoing={replacing}
              onDone={() => setReplacing(null)}
            />
          </Panel>
        )}
```

**(d)** In `SubstituteMemberForm`, put the designation choice above the person field and swap which control renders:

```tsx
        <FormField label="Who is joining" error={errors.memberType?.message}>
          <Select inputSize="sm" {...register('memberType')}>
            <option value="PARTY">A registered person</option>
            <option value="FREEFORM">Just their name</option>
          </Select>
          <p className="mt-1 text-xs text-muted-foreground">
            {/* Says what the choice buys, rather than warning about a restriction that does
                not exist. A name is always enough to insure someone on a scheme. */}
            A name is enough — a dependant or a child needs nothing more. Choose a registered
            person when this life is already a client, so their claims and other cover link up.
          </p>
        </FormField>

        {watchedMemberType === 'FREEFORM' ? (
          <FormField label="Full name" error={errors.memberName?.message}>
            <Input inputSize="sm" placeholder="Zawadi Mushi" {...register('memberName')} />
          </FormField>
        ) : (
          <FormField label="Person" error={errors.memberPartyId?.message}>
            <Controller
              control={control}
              name="memberPartyId"
              render={({ field }) => (
                <PartyPicker
                  value={field.value || null}
                  onChange={(partyId) => field.onChange(partyId ?? '')}
                  placeholder="Search employees by name…"
                />
              )}
            />
          </FormField>
        )}

        <FormField label="Takes effect" error={errors.effectiveFrom?.message}>
          <Input type="date" inputSize="sm" {...register('effectiveFrom')} />
        </FormField>
```

- [ ] **Step 7: Run the frontend checks**

```
cd frontend && npx vitest run ; npm run typecheck ; npm run lint
```

Expected: all green.

- [ ] **Step 8: Commit**

```bash
git add -A frontend/src
git commit -m "feat(console): substitute a scheme's lives, and name one without registering them"
```

---

## Task 7: End-to-end against the real stack

**Files:**
- Modify: `frontend/e2e/staff-group-schemes.spec.ts`

- [ ] **Step 1: Restart the dev stack on the new build**

Every migration in Tasks 2–5 must already be applied to the dev database (each task did so). Then:

```
cd backend && ./mvnw -o package -DskipTests
```

and restart the backend. Green Testcontainers tests do not prove the dev database is in sync — this platform applies migrations by hand.

- [ ] **Step 2: Rewrite the joiner test as a substitution test**

The existing `sets up a scheme whose total is derived from its members, then moves with a joiner` adds a member. Change its second half to open a member's **Replace** action, choose *Just their name*, type `Neema Joiner`, set the effective date to today, submit, and assert:

```ts
    // One out, one in. The count is the contract and it does not move.
    await expect(page.getByRole('row', { name: /Neema Joiner/ })).toBeVisible();
    await expect(page.getByText('Juma Leaver')).toBeVisible();
    await page.getByRole('button', { name: 'Left' }).click();
    await expect(page.getByRole('row', { name: /Juma Leaver/ })).toBeVisible();
```

Rename the test to `substitutes one life for another without changing the headcount`.

- [ ] **Step 3: Add a freeform-member test**

Follow the existing spec's own scheme-creation flow (`IssueGroupSchemePage`, reached from `/staff/group-schemes/new`), choosing *Just their name* on the opening-schedule row instead of picking a person:

```ts
  test('a life can be named on the schedule without becoming a client', async ({ page }) => {
    // The reason freeform members exist at all: registering 500 employees is 500 rows in a
    // KYC review queue nobody will ever action, in a register meant for people this tenant
    // has a relationship with. The employer is the client; their staff are lives.
    const employer = await registerEmployer(page, 'Freeform Employer Ltd');
    await page.goto('/staff/group-schemes/new');
    await fillSchemeBasics(page, { employer, benefitBasis: 'FLAT', flatBenefit: '5000000.00' });

    await page.getByRole('button', { name: 'Add a life' }).click();
    await page.getByLabel('Who is joining').selectOption('FREEFORM');
    await page.getByLabel('Full name').fill('Zawadi Mushi');
    await page.getByRole('button', { name: 'Issue scheme' }).click();

    // On the schedule.
    await expect(page.getByRole('row', { name: /Zawadi Mushi/ })).toBeVisible({ timeout: 15_000 });
    // Not a client, and specifically not sitting in the KYC queue.
    await page.goto('/staff/clients?kycStatus=PENDING');
    await expect(page.getByRole('row', { name: /Zawadi Mushi/ })).toHaveCount(0);
    // Nor anywhere in the register at all.
    await page.goto('/staff/clients');
    await page.getByPlaceholder('Search by name').fill('Zawadi');
    await expect(page.getByText(/No clients/)).toBeVisible({ timeout: 15_000 });
  });
```

`registerEmployer` and `fillSchemeBasics` are the helpers the existing spec already uses to reach the issue form — reuse them rather than writing new ones. If they are inline in the existing test rather than extracted, extract them first as part of this step, so both tests drive the same flow.

- [ ] **Step 4: Assert the employer was messaged**

On the scheme's master policy page (via the Contract link), the Messages panel must now show the scheme-live message:

```ts
    await expect(page.getByText(/Group scheme .* is now live/)).toBeVisible({ timeout: 15_000 });
```

- [ ] **Step 5: Run the group spec, then the whole e2e suite**

```
cd frontend && npx playwright test staff-group-schemes.spec.ts
cd frontend && npx playwright test
```

Run Playwright with no concurrent Maven build, and not across a machine sleep — both produce failures that are not code.

- [ ] **Step 6: Run the full backend suite**

```
cd backend && ./mvnw -o clean test
```

Expected: BUILD SUCCESS. Any failure here is real: the migration lists, the `MemberInput` widening and the removed `addMember` all reach classes that this plan never opened.

- [ ] **Step 7: Commit**

```bash
git add -A frontend/e2e
git commit -m "test(e2e): substitution, a freeform life, and the employer's scheme notice"
```

---

## Deferred, deliberately

- **Premium due and premium overdue messaging**, with a staff surface that approves automatic sending. No such message exists for individual policies either. This is its own build and its own plan.
- **A standalone member exit.** Substitution is the only way a life leaves a fixed-headcount scheme, and it carries the exit with it, so the `EXITED` state and the console's "Left" filter start working as a side effect of Task 4.
- **Opening an underwriting case for an above-FCL member.** `PolicyMember.referForEvidence(UUID)` exists and has no caller: a member over the limit is flagged `EVIDENCE_REQUIRED`, covered to the limit, and no case is ever opened — for registered and freeform lives alike. This plan neither closes the hole nor treats freeform members as a special case of it. When it is closed, the rule will be "a life whose excess is being underwritten must be a registered person by then", enforced at the point the case is opened rather than at the point the life is admitted to the schedule.
- **Bulk / CSV schedule upload.** Freeform members remove the reason a 500-life schedule was unbearable; whether typing 500 rows is still unbearable is a question best answered after somebody tries it.
