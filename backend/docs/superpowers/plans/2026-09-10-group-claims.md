# Group Claims Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a death claim on a group scheme name the life that died, pay what that life was covered for on the day it happened, remove them from the schedule, and leave the other 499 members insured.

**Architecture:** Four defects on one path, fixed in severity order so the worst stops first. A settled claim currently surrenders the whole master policy; Task 1 stops that with a guard, alone and shippable. Tasks 2–4 then build the real thing: `claims.claim` gains a `policy_member_id`, `PolicyApi` gains one method answering "what is claimable here on this date" — the member's effective-dated `covered_amount` on a scheme, the sum assured otherwise — and settlement discharges the member rather than the contract. `claims` already declares `policy::api` in its `allowedDependencies`, so no module boundary moves.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Flyway (per-module directories, applied by `scripts/migrate.sh`, NOT on boot), Postgres 16, Testcontainers, JUnit 5, AssertJ; React + TypeScript, Zustand, zod, Vitest, Playwright.

## Global Constraints

- **A settled claim on a group scheme must never change the master policy's status.** 499 people are still alive and still insured, and the employer still owes premium for them. Billing continuing to invoice is the CORRECT outcome here, and is the opposite of the individual-life case the current code was written for.
- **The figure a claim pays is `policy_member_benefit.covered_amount` in force ON THE DATE OF EVENT.** That table is effective-dated for precisely this reason ([build5 spec §2.4](../specs/2026-09-03-build5-group-business-design.md)). Never the scheme total, never today's row.
- A member's `covered_amount` is already the FCL-capped figure: `EVIDENCE_REQUIRED` and `DECLINED` both cover to the limit, `ACCEPTED` and `WITHIN_FCL` cover in full. **Do not re-apply the free cover limit at claim time** — that would cap an accepted excess twice.
- `claims` may depend on `policy::api, underwriting::api, party::api, document::api, refdata::api`. It must NOT reference `product::api` — a `ProductCategory` in claims' bytecode fails `ModularityTests`. Group-versus-individual branching therefore lives in `policy`, not in `claims`.
- Every new `claims` migration must be appended to the migration list in all **15** test classes that carry one; every new `policy` migration to all **37**. A migration in no test class has never run in any test schema.
- Migrations are NOT applied on boot (`spring.flyway.enabled: false`). Apply each by hand before any real-stack check: `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/<mod>/<file>.sql`
- After any record or method-signature change run `./mvnw -o clean test-compile`. Incremental compilation hides broken unchanged tests.
- Do not run Prettier. Do not run Maven inside Docker.

---

## The four defects, as measured

| # | Defect | Evidence |
|---|---|---|
| 1 | Settling one member's claim surrenders the whole scheme | [`PaymentEventListener.java:248`](../../../src/main/java/tz/co/nlolo/lifeplatform/claims/application/PaymentEventListener.java) calls `terminateForSettledClaim(policyNumber, …)`, which sets the master to `SURRENDERED`. No `GROUP_LIFE` guard exists anywhere on the claims path. |
| 2 | The claim never names the deceased | `claims.claim` has `policy_number` and `claimant_party_id` and no member column. `claimant_party_id` is who is *filing*. |
| 3 | The covered amount is never read | Registration checks `policy.sumAssuredAmount() > 0` — the **scheme total**, 2.5bn on a 500-life scheme. `policy_member_benefit` is unreachable from claims. |
| 4 | No upper bound on an approved amount | `Claim.approve` checks only `signum() > 0`. Platform-wide, not group-only. |

**There is no test anywhere that registers or settles a claim on a group scheme.** The whole path is unexercised, which is why all four survived.

---

## File Structure

**Backend — created**

| File | Responsibility |
|---|---|
| `db-migrations/claims/V5__claim_policy_member.sql` | `policy_member_id` on `claims.claim` |
| `src/main/java/tz/co/nlolo/lifeplatform/policy/api/ClaimableCoverView.java` | What may be claimed here, on this date |

**Backend — modified**

| File | Change |
|---|---|
| `claims/application/PaymentEventListener.java` | Task 1 guard, then passing the member and the date through |
| `claims/api/ClaimsApi.java` | `RegisterClaimRequest` grows `policyMemberId` |
| `claims/application/ClaimsApiImpl.java` | Member validation, claimable-amount check at registration and approval |
| `claims/domain/Claim.java` | Carries `policyMemberId`; `approve` takes a ceiling |
| `claims/infrastructure/*Dto.java`, `api/openapi/openapi-claims.yaml` | Wire shape |
| `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java` | `claimableCover(...)`, and `terminateForSettledClaim` learns about members |
| `api/asyncapi-events.yaml` | `policy.GroupMemberExited` |

**Frontend — modified**

`src/features/claims/RegisterClaimPage.tsx`, `claimRegisterForm.ts` (+ `.test.ts`), `src/api/claims.ts`, `src/features/claims/ClaimSettlementPanel.tsx`

---

## Task 1: Stop a member's claim from cancelling the scheme

Shippable alone, and worth shipping alone. Until the rest exists, a settled group claim will leave the deceased on the roll and the scheme total overstated by their cover — a bookkeeping error a person can correct. Today it silently uninsures everybody else, which they cannot.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyClaimClosureTest.java`

**Interfaces:**
- Consumes: `Policy.getProductCategory()` (already on the entity — `Policy.java:206` reads it).
- Produces: `terminateForSettledClaim` becomes a silent no-op on a `GROUP_LIFE` policy. Signature unchanged, so no caller moves.

- [ ] **Step 1: Write the failing test**

Add to `PolicyClaimClosureTest.java`:

```java
    @Test
    void aSettledClaimDoesNotCloseAGroupScheme() {
        // One employee dying must not uninsure the other 499. terminateForSettledClaim exists
        // for individual life, where the single insured life is now dead and the contract is
        // discharged. On a scheme it discharges one MEMBER, and the contract carries on.
        //
        // Billing continuing to invoice the employer is correct here, and is the exact concern
        // the closure was originally written to prevent -- on an individual policy, where it
        // was right.
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issueGroupSchemeFixture(tenantId);

        policyApi.terminateForSettledClaim(policyNumber, UUID.randomUUID(), "claims:test");

        assertEquals(PolicyStatus.ACTIVE, policyApi.getPolicy(policyNumber).status(),
            "a scheme survives its members");
    }

    @Test
    void aSettledClaimStillClosesAnIndividualPolicy() {
        // The guard must be narrow. Individual life is unchanged: one life, one contract, and a
        // settled death claim discharges it.
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issueActivePolicy(tenantId);

        policyApi.terminateForSettledClaim(policyNumber, UUID.randomUUID(), "claims:test");

        assertEquals(PolicyStatus.SURRENDERED, policyApi.getPolicy(policyNumber).status());
    }
```

`issueActivePolicy(tenantId)` is whatever the existing tests in this class already use to reach an ACTIVE policy — reuse it rather than writing a new one. Add `db-migrations/policy/V9__group_scheme_and_members.sql` to this class's migration list if it is not already there, then add this fixture:

```java
    private static final AtomicInteger GROUP_PHONE_SEQ = new AtomicInteger(9000);

    /** A one-life scheme, in force. The smallest thing the guard can be tested against. */
    private String issueGroupSchemeFixture(UUID tenantId) {
        TenantContext.set(tenantId);
        String code = "GRP-CLOSE-" + GROUP_PHONE_SEQ.incrementAndGet();
        ProductSummaryView product = productApi.createProduct(code, "Group Life " + code,
            ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        UUID employer = partyApi.registerIndividual("ABC Company", LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", GROUP_PHONE_SEQ.incrementAndGet()), null, "test").partyId();
        UUID life = partyApi.registerIndividual("Insured Life", LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", GROUP_PHONE_SEQ.incrementAndGet()), null, "test").partyId();

        return policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), versionId, null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(life, null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now(), null,
            "group onboarding"), "staff1").policyNumber();
    }
```

This needs `ProductApi` and `PartyApi` autowired into the class if they are not already.

- [ ] **Step 2: Run it and watch it fail**

```
cd backend && ./mvnw -o test -Dtest='PolicyClaimClosureTest#aSettledClaimDoesNotCloseAGroupScheme' -DfailIfNoTests=false
```

Expected: FAIL — `expected ACTIVE but was SURRENDERED`. That failure IS the bug; read it before fixing it.

- [ ] **Step 3: Add the guard**

In `PolicyApiImpl.terminateForSettledClaim`, immediately after `findPolicyOrThrow`:

```java
        // A SCHEME IS NOT DISCHARGED BY ONE MEMBER'S DEATH.
        //
        // This method closes a policy because a settled claim has discharged its coverage, and
        // billing must stop invoicing a contract that no longer covers anybody. Both halves are
        // true of individual life and false of a group scheme: the claim discharged ONE member,
        // the other 499 are alive and insured, and the employer still owes premium for them.
        // Without this guard one employee's death set the master policy to SURRENDERED and
        // uninsured the entire workforce -- silently, from an AFTER_COMMIT listener, with the
        // money already paid.
        //
        // A no-op rather than a throw: the claim is SETTLED and the disbursement has COMPLETED
        // before this runs. Throwing here would only fire the POLICY_CLOSURE_FAILED alert for a
        // case that needs no closure at all.
        //
        // Exiting the member -- which IS what a settled group claim should do -- is deliberately
        // not done here yet. It needs the claim to say which member died, which it cannot (see
        // Task 2). Leaving them on the roll overstates the scheme total by their cover, which is
        // a figure a person can correct; cancelling everybody's cover is not.
        if ("GROUP_LIFE".equals(policy.getProductCategory())) {
            log.info("Claim {} settled against group scheme {} -- the scheme is not closed; "
                + "the member's own exit is not yet wired", claimId, policyNumber);
            return;
        }
```

- [ ] **Step 4: Run both tests, then the policy and claims suites**

```
cd backend && ./mvnw -o test -Dtest='PolicyClaimClosureTest+ClaimSettlementEndToEndTest+ClaimsApiIntegrationTest' -DfailIfNoTests=false
```

Expected: PASS. The individual-life test proves the guard is narrow.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyClaimClosureTest.java
git commit -m "fix(policy): a settled member claim must not surrender the whole group scheme"
```

---

## Task 2: The claim names the life that died

**Files:**
- Create: `backend/db-migrations/claims/V5__claim_policy_member.sql`
- Modify: `claims/api/ClaimsApi.java`, `claims/domain/Claim.java`, `claims/application/ClaimsApiImpl.java`, `claims/infrastructure/RegisterClaimRequestDto.java`, `claims/infrastructure/ClaimResponseDto.java`, `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `backend/api/openapi/openapi-claims.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/GroupClaimIntegrationTest.java` (new)

**Interfaces:**
- Consumes: `PolicyView.productCategory()` (exposed since Build 5).
- Produces, for Tasks 3–5:
  - `record ClaimableCoverView(BigDecimal amount, String currencyCode, UUID policyMemberId)` in `policy.api` — `policyMemberId` is null on an individual policy
  - `ClaimableCoverView PolicyApi.claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf)` — throws `InvalidPolicyStateException` when a scheme is given no member, an individual policy is given one, the member is not on that scheme, or the member was not covered on `asOf`
  - `RegisterClaimRequest(String policyNumber, UUID policyMemberId, UUID claimantPartyId, ClaimType claimType, LocalDate dateOfEvent, ClaimDetails details)`
  - `ClaimView.policyMemberId()`

- [ ] **Step 1: Write the failing tests**

New `GroupClaimIntegrationTest.java`, modelled on `ClaimsApiIntegrationTest`'s container/migration setup plus `db-migrations/policy/V9__group_scheme_and_members.sql` and `db-migrations/claims/V5__claim_policy_member.sql`.

**Write this fixture block first.** Every test in Tasks 2–4 uses it, and it is the only place these helpers are defined:

```java
    @Autowired private PolicyApi policyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private PolicyMemberRepository policyMemberRepository;

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(7000);
    private UUID tenantId;
    private UUID claimant;
    /** Backdated, so "exited on the date of event" is distinguishable from "exited today". */
    private final LocalDate dateOfEvent = LocalDate.now().minusMonths(6);

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        claimant = person("Widow Claimant");
    }

    /** A scheme plus a lookup from a member's name to their id — the tests speak in names. */
    private record GroupFixture(String policyNumber, Map<String, UUID> membersByName) {
        UUID memberIdNamed(String name) {
            UUID id = membersByName.get(name);
            if (id == null) throw new AssertionError("No member named " + name);
            return id;
        }
    }

    private UUID person(String name) {
        return partyApi.registerIndividual(name, LocalDate.of(1985, 6, 15),
            "+2557" + String.format("%08d", PHONE_SEQ.incrementAndGet()), null, "test").partyId();
    }

    /**
     * Two lives at 5,000,000 each, commenced a year ago so a six-month-old event falls inside
     * cover. No free cover limit: the FCL is a separate concern, and a limit here would make
     * every covered amount a capped one — hiding a bug that returned the cap instead of the
     * benefit.
     *
     * <p>Members are registered people, which is `MemberInput`'s only shape TODAY. This plan
     * deliberately does not depend on the freeform-member work in
     * `2026-09-10-group-scheme-substitution-and-notices.md`; if that lands first, `MemberInput`
     * gains a leading `MemberType` and these two calls need `MemberType.PARTY` inserted.
     */
    private GroupFixture flatSchemeOfTwo(String productCode, String firstName, String secondName) {
        ProductSummaryView product = productApi.createProduct(productCode, "Group Life " + productCode,
            ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        UUID firstPartyId = person(firstName);
        UUID secondPartyId = person(secondName);
        GroupSchemeView scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            person("ABC Company"), product.productId(), versionId, null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(firstPartyId, null, null, null),
                    new PolicyApi.MemberInput(secondPartyId, null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", LocalDate.now().minusYears(1), null,
            "group onboarding"), "staff1");

        // Keyed off the party ids we just minted rather than off a name on the member row --
        // a member row holds no name of its own, which is the whole reason listMembers resolves
        // its search through PartyApi.
        Map<UUID, String> nameByParty = Map.of(firstPartyId, firstName, secondPartyId, secondName);
        Map<String, UUID> byName = policyApi
            .listMembers(scheme.policyNumber(), null, null, PageRequest.of(0, 25))
            .getContent().stream()
            .collect(Collectors.toMap(m -> nameByParty.get(m.memberPartyId()),
                                       PolicyMemberView::policyMemberId));
        return new GroupFixture(scheme.policyNumber(), byName);
    }

    private PolicyMemberView memberNamed(String policyNumber, String name) {
        return policyApi.listMembers(policyNumber, null, name, PageRequest.of(0, 25))
            .getContent().stream().findFirst()
            .orElseThrow(() -> new AssertionError("No member named " + name));
    }

    private ClaimDetails deathDetails() {
        return new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test");
    }

    /** Registers and assesses, leaving the claim UNDER_ASSESSMENT and ready to decide. */
    private UUID registerAndAssess(GroupFixture scheme, String memberName) {
        ClaimView claim = claimsApi.registerClaim(new RegisterClaimRequest(
            scheme.policyNumber(), scheme.memberIdNamed(memberName), claimant,
            ClaimType.DEATH, dateOfEvent, deathDetails()),
            "idem-" + UUID.randomUUID(), "clerk");
        claimsApi.submitAssessment(claim.claimId(), "Findings", new BigDecimal("5000000.00"),
            "TZS", false, "assessor");
        return claim.claimId();
    }

    /** The individual-policy counterpart, on a 2,000,000 sum assured. */
    private UUID registerAndAssessIndividual() {
        String policyNumber = issueIndividualPolicy();
        ClaimView claim = claimsApi.registerClaim(new RegisterClaimRequest(
            policyNumber, null, claimant, ClaimType.DEATH, dateOfEvent, deathDetails()),
            "idem-" + UUID.randomUUID(), "clerk");
        claimsApi.submitAssessment(claim.claimId(), "Findings", new BigDecimal("2000000"),
            "TZS", false, "assessor");
        return claim.claimId();
    }

    /**
     * An ACTIVE individual policy with a 2,000,000 sum assured, commenced a year ago.
     * Copy `ClaimsApiIntegrationTest`'s own policy fixture rather than writing a second one —
     * two fixtures for "a policy you can claim against" drift.
     */
    private String issueIndividualPolicy() { /* as ClaimsApiIntegrationTest does it */ }

    /**
     * Drives a claim all the way to SETTLED. The disbursement rail is what publishes
     * DisbursementCompleted, which is what triggers the discharge under test — so this must go
     * through the real path, not call the listener directly.
     * Copy the settlement sequence from `ClaimSettlementEndToEndTest`.
     */
    private void settleClaimFor(GroupFixture scheme, String memberName, BigDecimal amount) { /* … */ }

    /** No exit API exists and this plan adds none, so the test drives the entity directly. */
    private void exitMemberDirectly(UUID policyMemberId, LocalDate leftOn) {
        PolicyMember member = policyMemberRepository
            .findByPolicyMemberIdAndTenantId(policyMemberId, tenantId).orElseThrow();
        member.exit(leftOn);
        policyMemberRepository.save(member);
    }
```

Two of these are marked to copy rather than spelled out, and that is deliberate: `issueIndividualPolicy` and `settleClaimFor` must be the *same* sequences `ClaimsApiIntegrationTest` and `ClaimSettlementEndToEndTest` already use, and reproducing them here would create a second definition free to drift from the first. Open those two files and copy the bodies.

Now the tests:

```java
    @Test
    void aGroupClaimMustNameTheMemberWhoDied() {
        // On a 500-life scheme "somebody on GL-000123 died" is not a claim anyone can assess.
        TenantContext.set(tenantId);
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-01", "Juma Deceased", "Asha Living");

        assertThatThrownBy(() -> claimsApi.registerClaim(new RegisterClaimRequest(
                scheme.policyNumber(), null, claimant, ClaimType.DEATH, LocalDate.now(), deathDetails()),
                "idem-1", "clerk"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("names a member");
    }

    @Test
    void anIndividualClaimMayNotNameAMember() {
        // The mirror image, and not pedantry: a member id against an individual policy is a
        // caller who believes this contract has a schedule. Accepting and ignoring it would
        // pay out against a member row belonging to a different policy entirely.
        TenantContext.set(tenantId);
        String policyNumber = issueIndividualPolicy();

        assertThatThrownBy(() -> claimsApi.registerClaim(new RegisterClaimRequest(
                policyNumber, UUID.randomUUID(), claimant, ClaimType.DEATH, LocalDate.now(), deathDetails()),
                "idem-2", "clerk"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("is not a group scheme");
    }

    @Test
    void theMemberMustBeOnThisSchemeAndCoveredOnTheDateOfEvent() {
        TenantContext.set(tenantId);
        GroupFixture a = flatSchemeOfTwo("GRP-CLAIM-02", "Juma Deceased", "Asha Living");
        GroupFixture b = flatSchemeOfTwo("GRP-CLAIM-03", "Other Person", "Second Person");
        UUID memberOfB = b.memberIdNamed("Other Person");

        // Somebody else's employee.
        assertThatThrownBy(() -> claimsApi.registerClaim(new RegisterClaimRequest(
                a.policyNumber(), memberOfB, claimant, ClaimType.DEATH, LocalDate.now(), deathDetails()),
                "idem-3", "clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("is not a member of");

        // Covered from today, so yesterday they were not covered. This is the check that stops
        // a scheme being backdated into cover it never had.
        assertThatThrownBy(() -> claimsApi.registerClaim(new RegisterClaimRequest(
                a.policyNumber(), a.memberIdNamed("Juma Deceased"), claimant, ClaimType.DEATH,
                LocalDate.now().minusDays(1), deathDetails()), "idem-4", "clerk"))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("was not covered on");
    }

    @Test
    void aClaimForSomebodyWhoHasSinceLeftIsStillRegistrable() {
        // The schema keeps exited members precisely for this: "a claim can arrive after
        // somebody leaves". What matters is whether they were covered ON THE DATE OF EVENT,
        // not whether they are covered today.
        TenantContext.set(tenantId);
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-04", "Juma Deceased", "Asha Living");
        UUID memberId = scheme.memberIdNamed("Juma Deceased");
        exitMemberDirectly(memberId, LocalDate.now());

        ClaimView claim = claimsApi.registerClaim(new RegisterClaimRequest(
            scheme.policyNumber(), memberId, claimant, ClaimType.DEATH, LocalDate.now(), deathDetails()),
            "idem-5", "clerk");

        assertThat(claim.policyMemberId()).isEqualTo(memberId);
    }
```

`exitMemberDirectly` calls `PolicyMember.exit(...)` through the repository — there is no exit API yet and this plan does not add one.

- [ ] **Step 2: Run them and watch them fail**

```
cd backend && ./mvnw -o test -Dtest='GroupClaimIntegrationTest' -DfailIfNoTests=false
```

Expected: FAIL to compile — `RegisterClaimRequest` has five components and `ClaimView` has no `policyMemberId`.

- [ ] **Step 3: Write the migration**

`backend/db-migrations/claims/V5__claim_policy_member.sql`:

```sql
-- db-migrations/claims/V5__claim_policy_member.sql
-- Which life died.
--
-- claims.claim has carried policy_number and claimant_party_id since V1, and claimant_party_id
-- is who is FILING -- the widow -- not who died. On individual life that is enough, because the
-- policy names exactly one insured life. On a group scheme it is not: "somebody on GL-000123
-- died" is not a claim anyone can assess, value or pay.
--
-- The consequences were not theoretical. Registration checked policy.sum_assured_amount > 0,
-- which on a 500-life scheme is the TOTAL of everybody's cover -- 2.5bn where the dead member
-- was insured for 5m -- and Claim.approve had no upper bound at all. Meanwhile
-- policy_member_benefit.covered_amount, stored and effective-dated for the sole stated purpose
-- of being "the figure a claim pays on the date of event", had no reader.
--
-- NULLABLE, and it must stay nullable: an individual policy has no member and a member id
-- against one is a caller who believes that contract has a schedule. The rule is "required iff
-- the policy is GROUP_LIFE", which no single-row CHECK can express -- the product category
-- lives in another schema -- so it is enforced in ClaimsApiImpl and stated here.
--
-- No FOREIGN KEY to policy.policy_member, deliberately, and consistent with claim.policy_number
-- carrying no FK to policy.policy either. Cross-module FKs are not used on this platform: the
-- modules own their own schemas and the reference is validated through PolicyApi at write time.
ALTER TABLE claims.claim
    ADD COLUMN policy_member_id UUID;

COMMENT ON COLUMN claims.claim.policy_member_id IS
    'The insured life this claim is for, on a GROUP_LIFE policy. NULL on individual business, '
    'where the policy itself names the life. Validated against PolicyApi at registration; no FK, '
    'because policy owns that table.';

CREATE INDEX idx_claim_policy_member ON claims.claim (tenant_id, policy_member_id)
    WHERE policy_member_id IS NOT NULL;
```

- [ ] **Step 4: Add the migration to the 15 test classes that list claims migrations**

Use a pattern-only node script (shell string editing corrupts these files — CRLF endings defeat `\n` matching), anchored on V3, which all 15 carry:

```js
// scratchpad/add-claims-v5.js
const fs = require('fs');
const AFTER = 'db-migrations/claims/V3__registration_idempotency_key.sql';
const NEW = 'db-migrations/claims/V5__claim_policy_member.sql';
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
  // Preserve the list terminator: if the anchor was the last entry its line ends with the
  // closing paren, which has to move onto the new last entry instead.
  const tail = line.endsWith(',') ? '' : line.slice(line.lastIndexOf('"') + 1);
  const anchorLine = tail === '' ? line : line.slice(0, line.lastIndexOf('"') + 1) + ',';
  lines.splice(i, 1, anchorLine, indent + '"' + NEW + '"' + (tail === '' ? ',' : tail));
  fs.writeFileSync(f, lines.join(eol));
  changed++;
}
console.log('changed ' + changed + ' of ' + (process.argv.length - 2));
```

```bash
cd backend && node ../scratchpad/add-claims-v5.js $(grep -rl "db-migrations/claims/V3__registration_idempotency_key.sql" src/test/java)
grep -rl "V5__claim_policy_member" src/test/java | wc -l   # expect 15
```

- [ ] **Step 5: Add `claimableCover` to PolicyApi**

`backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/ClaimableCoverView.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What may be claimed against this contract, for this life, on this date.
 *
 * <p>One answer for two very different contracts, which is the point: {@code claims} must not
 * branch on product category. It cannot, in fact — {@code product::api} is not among its
 * allowed dependencies, so a {@code ProductCategory} in claims' bytecode fails
 * {@code ModularityTests}. Group-versus-individual is decided in {@code policy}, where the
 * member schedule lives.
 *
 * @param amount on a group scheme, the member's {@code covered_amount} in force on the date of
 *     event — already capped at the free cover limit where the limit bit, so it must NOT be
 *     capped again. On individual business, the policy's sum assured.
 * @param policyMemberId echoed back on a group scheme, null on individual business.
 */
public record ClaimableCoverView(BigDecimal amount, String currencyCode, UUID policyMemberId) {}
```

In `PolicyApi.java`:

```java
    /**
     * What a claim against this contract may pay, for this life, as at {@code asOf}.
     *
     * <p><b>As at the date of event, not today.</b> {@code policy_member_benefit} is
     * effective-dated for exactly this reason: a death two years ago must be valued at the
     * cover in force then, not at a benefit restated at a renewal since.
     *
     * @param policyMemberId REQUIRED on a GROUP_LIFE policy and REJECTED on any other. A scheme
     *     with no member named cannot be valued — its sum assured is 500 people's cover added
     *     together — and a member named against an individual policy is a caller who believes
     *     that contract has a schedule.
     * @throws InvalidPolicyStateException if the member is missing, given where it does not
     *     belong, not a member of this scheme, or was not covered on {@code asOf}
     * @throws PolicyNotFoundException if no such policy exists in this tenant
     */
    ClaimableCoverView claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf);
```

In `PolicyApiImpl.java`:

```java
    @Override
    @Transactional(readOnly = true)
    public ClaimableCoverView claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);

        if (!"GROUP_LIFE".equals(policy.getProductCategory())) {
            if (policyMemberId != null) {
                throw new InvalidPolicyStateException("Policy " + policyNumber
                    + " is not a group scheme, so a claim on it cannot name a member");
            }
            return new ClaimableCoverView(policy.getSumAssuredAmount(),
                policy.getSumAssuredCurrency(), null);
        }
        if (policyMemberId == null) {
            throw new InvalidPolicyStateException("Scheme " + policyNumber
                + " insures many lives, so a claim on it names a member");
        }

        GroupScheme scheme = findSchemeOrThrow(policyNumber, tenantId);
        PolicyMember member = policyMemberRepository
            .findByPolicyMemberIdAndTenantId(policyMemberId, tenantId)
            .filter(m -> m.getPolicyNumber().equals(policyNumber))
            .orElseThrow(() -> new InvalidPolicyStateException("Member " + policyMemberId
                + " is not a member of scheme " + policyNumber));

        // Covered ON THE DATE OF EVENT, not covered today. An exited member is a legitimate
        // claimant for an event that happened while they were still on the schedule -- which is
        // why V9 keeps exited rows rather than deleting them.
        if (asOf.isBefore(member.getJoinedOn())
                || (member.getLeftOn() != null && asOf.isAfter(member.getLeftOn()))) {
            throw new InvalidPolicyStateException("Member " + policyMemberId
                + " was not covered on " + asOf + " (covered from " + member.getJoinedOn()
                + (member.getLeftOn() != null ? " to " + member.getLeftOn() : "") + ")");
        }

        // covered_amount, NOT benefit_amount, and NOT re-capped at the FCL. The stored covered
        // amount already IS the capped figure where the limit bit -- EVIDENCE_REQUIRED and
        // DECLINED both sit at the limit, ACCEPTED sits at the full benefit. Applying the limit
        // again here would halve an accepted excess the underwriter had granted.
        // findInForce is (memberId, tenantId, asOf, Pageable) and returns rows newest-effective
        // first; the Pageable is how it takes only the one in force. PageRequest.of(0, 1) rather
        // than fetching the history and discarding it -- a long-running scheme accumulates a row
        // per restatement per member.
        //
        // NOTE: this is currently the repository's FIRST caller. The bulk sibling
        // findInForceForMembers backs the member list; findInForce itself was written for exactly
        // this use and has never been invoked.
        BigDecimal covered = policyMemberBenefitRepository
            .findInForce(policyMemberId, tenantId, asOf, PageRequest.of(0, 1))
            .stream().findFirst()
            .map(PolicyMemberBenefit::getCoveredAmount)
            .orElseThrow(() -> new InvalidPolicyStateException("Member " + policyMemberId
                + " has no benefit in force on " + asOf));

        return new ClaimableCoverView(covered, scheme.getCurrency(), policyMemberId);
    }
```

- [ ] **Step 6: Carry the member on the claim**

`RegisterClaimRequest` grows `policyMemberId` as its **second** component:

```java
    record RegisterClaimRequest(String policyNumber, UUID policyMemberId, UUID claimantPartyId,
                                 ClaimType claimType, LocalDate dateOfEvent, ClaimDetails details) {}
```

`Claim` gains `@Column(name = "policy_member_id") private UUID policyMemberId;`, a constructor parameter and a getter. `ClaimView` gains `policyMemberId`. In `ClaimsApiImpl.registerClaim`, replace the sum-assured check at step 3 with:

```java
        // 3. What is actually claimable here, for this life, on the date of the event.
        //
        //    This replaces a `policy.sumAssuredAmount() > 0` check whose comment explained at
        //    length why a per-benefit check was not possible -- all true, and all about a
        //    different question. On a group scheme that check read the TOTAL of 500 members'
        //    cover, so a 5m death claim was validated against 2.5bn, and the member's own
        //    effective-dated covered_amount was never consulted by anything.
        //
        //    policy answers it, not claims: the member schedule is policy's, and claims cannot
        //    see ProductCategory without breaking its module boundary.
        ClaimableCoverView claimable = policyApi.claimableCover(
            request.policyNumber(), request.policyMemberId(), request.dateOfEvent());
        if (claimable.amount() == null || claimable.amount().signum() <= 0) {
            throw new ClaimValidationException("Policy " + request.policyNumber()
                + " has no positive cover to claim against on " + request.dateOfEvent());
        }
```

`claimableCover` throws `InvalidPolicyStateException` for the member rules; let it propagate, exactly as `PolicyNotFoundException` already does.

- [ ] **Step 7: Follow the contract**

In `backend/api/openapi/openapi-claims.yaml`, add to `RegisterClaimRequest`:

```yaml
        policyMemberId:
          description: >-
            The insured life this claim is for. REQUIRED when the policy is a group scheme —
            a scheme insures many lives and "somebody on this policy died" cannot be valued —
            and REJECTED on individual business, where the policy names the life itself.
            Either mistake is a 409.
          type: [string, "null"]
          format: uuid
```

and add `policyMemberId` (nullable uuid) to the claim response schema.

- [ ] **Step 8: Compile clean and run**

```
cd backend && ./mvnw -o clean test-compile && ./mvnw -o test -Dtest='GroupClaimIntegrationTest+ClaimsApiIntegrationTest+ClaimsContractTest+ClaimControllerValidationContractTest' -DfailIfNoTests=false
```

Expected: BUILD SUCCESS. Every existing `RegisterClaimRequest` construction site needs `null` inserted as the second argument; the compiler will list them.

- [ ] **Step 9: Apply to the dev database and commit**

```bash
cd backend
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < db-migrations/claims/V5__claim_policy_member.sql
git add -A backend/db-migrations/claims backend/src backend/api/openapi/openapi-claims.yaml
git commit -m "feat(claims): a group claim names the life that died, and is valued from their cover"
```

---

## Task 3: A claim cannot be approved for more than is covered

`Claim.approve` checks only that the amount is positive. There is no ceiling on any policy, group or individual — an adjudicator can approve a scheme's entire 2.5bn total for one death and nothing objects.

**Files:**
- Modify: `claims/domain/Claim.java`, `claims/application/ClaimsApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/GroupClaimIntegrationTest.java`, `ClaimsApiIntegrationTest.java`

**Interfaces:**
- Consumes: `ClaimableCoverView PolicyApi.claimableCover(...)` from Task 2.
- Produces: `Claim.approve(BigDecimal approvedAmount, String approvedCurrency, BigDecimal ceiling)`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void aGroupClaimCannotBeApprovedForMoreThanTheMemberWasCoveredFor() {
        // The 500x error. The scheme total is 10,000,000 across two lives; this member was
        // covered for 5,000,000, and that is the ceiling -- not the contract's sum assured.
        TenantContext.set(tenantId);
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-05", "Juma Deceased", "Asha Living");
        UUID claimId = registerAndAssess(scheme, "Juma Deceased");

        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, true,
                new BigDecimal("10000000.00"), "TZS", null, "payee-1", "idem-a", "manager"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("5000000.00");

        claimsApi.decideSettlement(claimId, true, new BigDecimal("5000000.00"), "TZS", null,
            "payee-1", "idem-b", "manager");
        assertThat(claimsApi.getClaim(claimId).approvedAmount())
            .isEqualByComparingTo(new BigDecimal("5000000.00"));
    }

    @Test
    void anIndividualClaimCannotBeApprovedForMoreThanTheSumAssured() {
        // Same ceiling, different source. This one was never bounded either.
        TenantContext.set(tenantId);
        UUID claimId = registerAndAssessIndividual();   // 2,000,000 sum assured

        assertThatThrownBy(() -> claimsApi.decideSettlement(claimId, true,
                new BigDecimal("2000000.01"), "TZS", null, "payee-1", "idem-c", "manager"))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("2000000");
    }

    @Test
    void approvingExactlyTheCoveredAmountIsAllowed() {
        // The boundary is inclusive: a death claim normally pays the whole sum assured, so an
        // exclusive bound would refuse the commonest correct settlement on the platform.
        TenantContext.set(tenantId);
        UUID claimId = registerAndAssessIndividual();
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "payee-1", "idem-d", "manager");
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.APPROVED);
    }
```

- [ ] **Step 2: Run them and watch the first two fail**

```
cd backend && ./mvnw -o test -Dtest='GroupClaimIntegrationTest' -DfailIfNoTests=false
```

Expected: the two ceiling tests FAIL (the over-approval succeeds), the exact-amount test PASSES.

- [ ] **Step 3: Add the ceiling**

In `Claim.java`:

```java
    /**
     * @param ceiling the most this claim may pay — the member's covered amount on a group
     *     scheme, the sum assured on individual business. INCLUSIVE: a death claim normally
     *     pays the whole of it, and an exclusive bound would refuse the commonest correct
     *     settlement on the platform.
     */
    public void approve(BigDecimal approvedAmount, String approvedCurrency, BigDecimal ceiling) {
        ...
        if (approvedAmount == null || approvedAmount.signum() <= 0) {
            throw new ClaimValidationException("Approved amount must be positive");
        }
        // Until now the only check was that the amount was positive, on every policy. Nothing
        // stopped one member's death claim being approved for a 500-life scheme's entire total.
        if (ceiling != null && approvedAmount.compareTo(ceiling) > 0) {
            throw new ClaimValidationException("Approved amount " + approvedAmount
                + " exceeds the " + ceiling + " this claim is covered for");
        }
        ...
    }
```

In `ClaimsApiImpl.decideSettlement`, before `claim.approve(...)`, resolve the ceiling from the claim's own stored facts — its policy, its member, its date of event — never from the caller:

```java
            ClaimableCoverView claimable = policyApi.claimableCover(
                claim.getPolicyNumber(), claim.getPolicyMemberId(), claim.getDateOfEvent());
            claim.approve(approvedAmount, approvedCurrency, claimable.amount());
```

- [ ] **Step 4: Run the claims module**

```
cd backend && ./mvnw -o clean test-compile && ./mvnw -o test -Dtest='GroupClaimIntegrationTest+ClaimsApiIntegrationTest+ClaimSettlementEndToEndTest+ClaimsContractTest' -DfailIfNoTests=false
```

Expected: PASS. Every existing test approves exactly the 2,000,000 sum assured of its fixture policy, so the inclusive bound admits all of them unchanged — verified before writing this plan.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "fix(claims): bound an approved claim by what the life was actually covered for"
```

---

## Task 4: Settlement exits the member and restates the scheme

Task 1's guard leaves the deceased on the roll. This replaces it with what should happen.

**Files:**
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`, `claims/application/PaymentEventListener.java`, `backend/api/asyncapi-events.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/GroupClaimIntegrationTest.java`, `PolicyClaimClosureTest.java`

**Interfaces:**
- Consumes: `PolicyMember.exit(LocalDate)` (on the entity since V9, still uncalled), `restateSchemeTotal(Policy, UUID, LocalDate)`, `Claim.getPolicyMemberId()` and `getDateOfEvent()` from Task 2.
- Produces:
  - `void PolicyApi.dischargeForSettledClaim(String policyNumber, UUID policyMemberId, LocalDate dateOfEvent, UUID claimId, String dischargedBy)` — replaces `terminateForSettledClaim`
  - event `policy.GroupMemberExited` with keys `policyNumber`, `policyMemberId`, `leftOn`, `reason`, `schemeTotalCovered`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void settlingAMembersClaimExitsThemAndRestatesTheSchemeWithoutClosingIt() {
        TenantContext.set(tenantId);
        GroupFixture scheme = flatSchemeOfTwo("GRP-CLAIM-06", "Juma Deceased", "Asha Living");
        BigDecimal totalBefore = policyApi.getGroupScheme(scheme.policyNumber()).totalCoveredAmount();
        assertThat(totalBefore).isEqualByComparingTo(new BigDecimal("10000000.00"));

        settleClaimFor(scheme, "Juma Deceased", new BigDecimal("5000000.00"));

        // The scheme lives.
        assertThat(policyApi.getPolicy(scheme.policyNumber()).status()).isEqualTo(PolicyStatus.ACTIVE);
        // The deceased is off the roll, dated to the event and not to the payment run.
        PolicyMemberView deceased = memberNamed(scheme.policyNumber(), "Juma Deceased");
        assertThat(deceased.status()).isEqualTo(MemberStatus.EXITED);
        assertThat(deceased.leftOn()).isEqualTo(dateOfEvent);
        // And the contract total is what the survivors are covered for.
        assertThat(policyApi.getGroupScheme(scheme.policyNumber()).totalCoveredAmount())
            .as("a dead member must stop contributing to the scheme's sum assured")
            .isEqualByComparingTo(new BigDecimal("5000000.00"));
        // The survivor is untouched.
        assertThat(memberNamed(scheme.policyNumber(), "Asha Living").status())
            .isEqualTo(MemberStatus.ACTIVE);
    }
```

- [ ] **Step 2: Run it and watch it fail**

Expected: FAIL — the member is still ACTIVE and the total is still 10,000,000, because Task 1's guard returns without doing anything.

- [ ] **Step 3: Replace the guard with the discharge**

Rename `terminateForSettledClaim` to `dischargeForSettledClaim` with the wider signature, and replace Task 1's early return:

```java
        // A settled claim discharges COVER. What that means depends on the contract.
        //
        // Individual life: the one insured life is dead, the contract is over, and billing must
        // stop invoicing it. Group: it discharges ONE MEMBER. The scheme carries on, the other
        // lives stay insured, and the employer still owes premium for them -- so the master
        // policy's status is deliberately untouched and billing deliberately continues.
        //
        // The exit is dated to the DATE OF EVENT, not to the day the payment cleared. A death in
        // March settled in September means the member stopped being covered in March; dating it
        // to September would leave them in the scheme total for six months they were not alive.
        if ("GROUP_LIFE".equals(policy.getProductCategory())) {
            PolicyMember member = policyMemberRepository
                .findByPolicyMemberIdAndTenantId(policyMemberId, tenantId)
                .filter(m -> m.getPolicyNumber().equals(policyNumber))
                .orElseThrow(() -> new InvalidPolicyStateException("Member " + policyMemberId
                    + " is not a member of scheme " + policyNumber));
            if (MemberStatus.EXITED.name().equals(member.getStatus())) {
                return; // idempotent on redelivery, same shape as alreadyClosed above
            }
            member.exit(dateOfEvent);
            policyMemberRepository.save(member);
            policyMemberBenefitRepository.flush();
            BigDecimal total = restateSchemeTotal(policy, tenantId, LocalDate.now());
            eventPublisher.publishEvent(DomainEventEnvelope.of("policy.GroupMemberExited", tenantId,
                Map.of("policyNumber", policyNumber,
                       "policyMemberId", policyMemberId,
                       "leftOn", dateOfEvent.toString(),
                       "reason", "CLAIM_SETTLED",
                       "schemeTotalCovered", Map.of("amount", total.toPlainString(),
                           "currencyCode", findSchemeOrThrow(policyNumber, tenantId).getCurrency()))));
            return;
        }
```

In `PaymentEventListener`, widen `SettledClaimFacts` to carry `policyMemberId` and `dateOfEvent` (both already on the loaded `Claim` in phase 1) and pass them through at the call site.

- [ ] **Step 4: Run the claims and policy suites**

```
cd backend && ./mvnw -o clean test-compile && ./mvnw -o test -Dtest='GroupClaimIntegrationTest+PolicyClaimClosureTest+ClaimSettlementEndToEndTest' -DfailIfNoTests=false
```

Expected: PASS, including Task 1's `aSettledClaimStillClosesAnIndividualPolicy`, which is what proves the individual path survived the rename.

- [ ] **Step 5: Catalogue the event**

Add `policy.GroupMemberExited` to `backend/api/asyncapi-events.yaml` with the keys above. Note in its description that **`regreporting` does not consume it**, so `policy_dimension`'s sum assured goes stale after a member exits — the same known gap `GroupMemberAdded` already has, now reachable by a second route.

- [ ] **Step 6: Prove the exit date is falsifiable**

Temporarily change `member.exit(dateOfEvent)` to `member.exit(LocalDate.now())` and confirm the `leftOn` assertion FAILS when `dateOfEvent` is backdated in the fixture. Restore. If the fixture's date of event is today, the test proves nothing — set it to `LocalDate.now().minusMonths(6)` first.

- [ ] **Step 7: Commit**

```bash
git add -A backend/src backend/api
git commit -m "feat(policy): a settled group claim exits the member and restates the scheme"
```

---

## Task 5: The console names the member and shows the ceiling

**Files:**
- Modify: `frontend/src/features/claims/RegisterClaimPage.tsx`, `claimRegisterForm.ts` (+ `.test.ts`), `src/api/claims.ts`, `src/api/types.ts` (regenerated), `src/features/claims/ClaimSettlementPanel.tsx`

**Interfaces:**
- Consumes: `policyMemberId` on the register request, `policyMemberId` on the claim view, and `GET /group-schemes/{n}/members` (already exists) for the picker.

- [ ] **Step 1: Regenerate types and write the failing form tests**

```
cd frontend && npm run generate:types
```

```ts
  it('requires a member when the policy is a group scheme', () => {
    expect(errorsFor({ productCategory: 'GROUP_LIFE' }, values({ policyMemberId: '' })))
      .toHaveProperty('policyMemberId');
  });

  it('rejects a member on an individual policy', () => {
    expect(errorsFor({ productCategory: 'TERM_LIFE' }, values({ policyMemberId: SOME_UUID })))
      .toHaveProperty('policyMemberId');
  });

  it('accepts an individual claim with no member', () => {
    expect(errorsFor({ productCategory: 'TERM_LIFE' }, values({ policyMemberId: '' }))).toEqual({});
  });
```

- [ ] **Step 2: Run them and watch them fail**

```
cd frontend && npx vitest run src/features/claims/claimRegisterForm.test.ts
```

Expected: FAIL — `policyMemberId` is not on `ClaimRegisterFormValues`.

- [ ] **Step 3: Add the field and the rule**

`ClaimRegisterFormValues` grows `policyMemberId: string`, and `claimRegisterFormSchema` takes `productCategory` in its context — required and UUID-shaped when `GROUP_LIFE`, required-empty otherwise. The page already loads the policy detail (`usePolicyStore(selectDetail(policyNumber))`), so the category is in hand without a new fetch.

- [ ] **Step 4: Render the picker and the ceiling**

In `RegisterClaimPage.tsx`, after the Policy field:

```tsx
        {policy.data?.productCategory === 'GROUP_LIFE' && (
          <FormField label="Who died" error={errors.policyMemberId?.message}>
            {/* A scheme insures many lives, so "a claim on GL-000123" names none of them.
                The roll is the only place the answer exists. */}
            <SchemeMemberPicker
              policyNumber={policyNumber}
              value={watch('policyMemberId') || null}
              onChange={(id) => setValue('policyMemberId', id ?? '')}
            />
            <p className="mt-1 text-[11px] text-subtle-foreground">
              Members who have left are listed too — a claim can arrive after somebody leaves,
              and what matters is whether they were covered on the date of event.
            </p>
          </FormField>
        )}
```

`SchemeMemberPicker` is a small new component over the existing `GET /group-schemes/{n}/members` endpoint, listing every member (no status filter) with their in-force cover beside the name.

In `ClaimSettlementPanel.tsx`, show the ceiling beside the approved-amount input, so an adjudicator sees the limit before the server refuses it rather than after.

- [ ] **Step 5: Run the frontend checks**

```
cd frontend && npx vitest run ; npm run typecheck ; npm run lint
```

- [ ] **Step 6: Commit**

```bash
git add -A frontend/src
git commit -m "feat(console): name the member a group claim is for, and show what they are covered for"
```

---

## Task 6: End to end against the real stack

- [ ] **Step 1: Rebuild and restart the dev stack**

All three migrations (`claims/V5`, plus nothing else — Tasks 3 and 4 are code-only) must already be applied. Then `./mvnw -o package -DskipTests` and restart. Green Testcontainers tests do not prove the dev database is in sync.

- [ ] **Step 2: Add the e2e spec**

In `frontend/e2e/staff-claims-adjudication.spec.ts`, add a group claim walking the whole path: issue a two-life scheme, register a death claim naming one member, assess, approve for that member's cover, settle, then assert on the scheme page that the deceased shows as **Left**, the survivor is **Active**, the headcount and total have dropped by one member, and — the assertion this whole plan exists for — the scheme's own status is still **Active**.

```ts
    // The defect this plan was written for: before it, settling one member's claim set the
    // master policy to Surrendered and uninsured everybody else on the schedule.
    await expect(page.getByText('Active', { exact: true })).toBeVisible();
```

Note: `staff-claims-adjudication` already runs at ~55s against a 60s default and carries a `test.slow()`. Adding a whole settlement journey to that file may push it over; put the group case in its own `test.slow()` block, and if the file becomes unreliable, split it rather than raising the global timeout.

- [ ] **Step 3: Run the group and claims specs, then the whole suite**

```
cd frontend && npx playwright test staff-claims-adjudication.spec.ts staff-group-schemes.spec.ts
cd frontend && npx playwright test
```

No concurrent Maven build, and not across a machine sleep — both produce failures that are not code.

- [ ] **Step 4: Run the full backend suite**

```
cd backend && ./mvnw -o clean test
```

Expected: BUILD SUCCESS. `RegisterClaimRequest` gained a component and `terminateForSettledClaim` was renamed, so this reaches classes this plan never opened.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/e2e
git commit -m "test(e2e): a settled group claim exits one life and leaves the scheme in force"
```

---

## Deliberately not in this plan

- **A standalone member exit API.** Death is the only exit this plan creates, and it arrives through settlement. Exits for leavers belong with the substitution work.
- **`regreporting` consuming `GroupMemberExited`.** `policy_dimension`'s sum assured already goes stale after `GroupMemberAdded`, which has no consumer either. This adds a second route to a known gap rather than a new one, and fixing it is a regreporting task.
- **Who gets paid.** A group death claim pays the claimant, exactly as an individual one does. That is right for an employer scheme, where the family claims. It is NOT right for credit life, where the payout extinguishes a debt owed to the lender — but credit life does not exist yet and is blocked on its own client question.
- **Re-checking the free cover limit at claim time.** `covered_amount` is already the capped figure. Applying the limit again would halve an accepted excess.
- **Claims against a policy that lapsed after the event.** A pre-existing, documented defect in `registerClaim` step 2: the in-force check ignores the date passed to it. Unrelated to group, and its fix is a date-bounded coverage query inside `policy`.
