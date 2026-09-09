# Offer and Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A policy goes in force when its first premium clears, not when an underwriter accepts.

**Architecture:** An accepted decision creates the policy in `PROPOSED`, and the premium on that record IS the offer. `billing.PremiumCollected` activates it and publishes a new `policy.PolicyActivated`. Distribution, reinsurance and regreporting move from `PolicyIssued` to `PolicyActivated`, because commission, cession and the regulatory count are all statements about a contract actually on risk. A pg_cron sweep closes unpaid offers to `NOT_TAKEN_UP` after a refdata-configured 30 days. Manual issue carries a required `issuanceBasis` whose value decides whether cover starts immediately.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Flyway (per-module directories), Postgres 16 with pg_cron, Testcontainers, JUnit 5. Frontend: React + TypeScript, Zustand, zod, Vitest, Playwright.

**Design spec:** `docs/superpowers/specs/2026-09-09-offer-and-acceptance-design.md`

## Global Constraints

- **Never run Maven in Docker.** Use `./mvnw` on the host. Testcontainers networking breaks otherwise.
- **After any record/DTO/interface signature change, run `./mvnw clean test-compile`.** Incremental compile does not recompile unchanged tests against a widened record; a scoped run stays green while `clean` surfaces real breaks.
- **Never edit Java sources or `api/openapi/*.yaml` while a Maven build is running.** It produces `ClassNotFoundException` on anonymous inner classes and phantom failures across untouched modules. Wait for the build.
- **Never run Prettier.** Not a dependency here; it reformats the whole tree.
- **Migrations live in `backend/db-migrations/<module>/`.** Next free numbers: `policy/V11`, `refdata/V5`.
- **`db-migrations/_post-migration/*.sql` is NOT applied by `scripts/migrate.sh`.** Those are ops files applied by hand. A new sweep goes there and must be applied manually to the dev database.
- **Every test class lists its migrations explicitly.** A new migration must be added to each `MigrationTestSupport.applyMigration(...)` call that needs it — 36 files list the underwriting ones. Use the pattern-only node script approach, not a shell one-liner with backticks.
- **The dev backend and its migrations are decoupled.** A new migration needs a manual apply plus a backend restart before real-stack e2e reflects it.
- **Keycloak role descriptions must stay under 255 characters** (`KEYCLOAK_ROLE.DESCRIPTION` is `varchar(255)`); a longer one makes the whole realm unimportable on a fresh bootstrap.
- **Bean names collide.** `billing`, `distribution`, `reinsurance` and `regreporting` each declare a `PolicyEventListener`; regreporting's is `@Component("regreportingPolicyEventListener")` for that reason.
- Frontend lint bans synchronous `setState` in a `useEffect` body. Regenerate types with `npm run generate:api` after any OpenAPI change.

---

## File Structure

**policy — the lifecycle change**
- `db-migrations/policy/V11__not_taken_up_status.sql` (create) — widen the status CHECK.
- `policy/api/PolicyStatus.java` (modify) — add `NOT_TAKEN_UP`.
- `policy/api/IssuanceBasis.java` (create) — the five bases and which start cover.
- `policy/domain/Policy.java` (modify) — `markNotTakenUp()`; `activate` stays as-is.
- `policy/api/PolicyApi.java` (modify) — `IssueRequest.issuanceBasis`; `activateOnFirstPremium`.
- `policy/application/PolicyApiImpl.java` (modify) — conditional activation at issue; activation method.
- `policy/application/PremiumCollectedEventListener.java` (create) — the activation trigger.
- `policy/infrastructure/ManualIssueRequestDto.java` (modify) — required `issuanceBasis`.
- `policy/infrastructure/PolicyController.java` (modify) — pass it through.

**The three listeners that move**
- `distribution/application/PolicyEventListener.java`, `reinsurance/application/PolicyEventListener.java`, `regreporting/application/PolicyEventListener.java` (modify) — one case label each.

**billing — stop chasing people who have not accepted**
- `db-migrations/_post-migration/configure-billing-sweep.sql` (modify) — scope arrears to in-force.

**The sweep**
- `db-migrations/refdata/V5__seed_offer_validity.sql` (create) — the 30-day window.
- `db-migrations/_post-migration/configure-offer-expiry-sweep.sql` (create) — the pg_cron job.

**Spec and docs**
- `api/openapi/openapi-policy.yaml`, `api/asyncapi-events.yaml`, `docs/05-event-catalog.md` (modify).

**Frontend**
- `src/features/policies/policyIssueForm.ts`, `IssuePolicyPage.tsx` (modify) — the basis selector.
- `src/features/policies/PolicyDetailPage.tsx` (modify) — an offer reads differently from a policy.

---

## Task 1: `NOT_TAKEN_UP` and the issuance basis

**Files:**
- Create: `backend/db-migrations/policy/V11__not_taken_up_status.sql`, `policy/api/IssuanceBasis.java`
- Modify: `policy/api/PolicyStatus.java`, `policy/domain/Policy.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Produces: `PolicyStatus.NOT_TAKEN_UP`; `IssuanceBasis` with `startsCoverImmediately()`; `Policy.markNotTakenUp()`.

- [ ] **Step 1: Write the migration**

```sql
-- db-migrations/policy/V11__not_taken_up_status.sql
-- An offer nobody took up.
--
-- Cover now starts when the first premium clears, so a policy exists in PROPOSED from the
-- moment an underwriter accepts. Most are paid. Some are not, and those need somewhere to end
-- that is not LAPSED -- lapsing is what happens to an IN-FORCE policy whose premiums stop, and
-- calling this that would put contracts that were never on risk into the lapse figures.
--
-- Around 15% of decided cases per the SOA new-business data, so this is an ordinary outcome
-- rather than an error path.
ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS policy_status_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED','NOT_TAKEN_UP'));

COMMENT ON COLUMN policy.policy.status IS
    'PROPOSED is an offer awaiting its first premium. NOT_TAKEN_UP is terminal: the offer expired unpaid. Neither is in force.';
```

Check the real constraint name first with:
`docker exec infra-postgres-1 psql -U postgres -d lifeplatform -c "\d policy.policy"` — if it differs from `policy_status_check`, use the actual one.

- [ ] **Step 2: Write the failing test**

```java
@Test
void anOfferThatExpiresIsNotTakenUpRatherThanLapsed() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-NTU-01");
    String policyNumber = issueDirectly(tenantId, fixture, List.of());

    TenantContext.set(tenantId);
    Policy policy = policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).orElseThrow();
    ReflectionTestUtils.setField(policy, "status", "PROPOSED");
    policy.markNotTakenUp();
    policyRepository.save(policy);

    assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.NOT_TAKEN_UP);
}

@Test
void onlyAProposedPolicyCanBecomeNotTakenUp() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-NTU-02");
    String policyNumber = issueDirectly(tenantId, fixture, List.of());

    TenantContext.set(tenantId);
    Policy active = policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).orElseThrow();
    // An ACTIVE policy is on risk. Expiring it as an unpaid offer would silently drop cover.
    assertThrows(InvalidPolicyStateException.class, active::markNotTakenUp);
}
```

`policyRepository` is not yet autowired in this test class — add `@Autowired private PolicyRepository policyRepository;` alongside the existing ones.

- [ ] **Step 3: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#anOfferThatExpiresIsNotTakenUpRatherThanLapsed'`
Expected: compilation failure — `markNotTakenUp()` does not exist.

- [ ] **Step 4: Add the status and the basis**

`PolicyStatus.java`:

```java
public enum PolicyStatus { PROPOSED, ACTIVE, LAPSED, SUSPENDED, SURRENDERED, MATURED, REINSTATED, NOT_TAKEN_UP }
```

`policy/api/IssuanceBasis.java`:

```java
package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Why a policy was issued by hand instead of through the normal decision path.
 *
 * <p>Required on {@code POST /policies/manual-issue}, and the value decides whether cover
 * starts immediately or waits for the first premium like any other new business.
 *
 * <p>That distinction is the whole point of the enum. "Manual issue always starts cover" would
 * make the exception path a way to skip the money rule for ordinary business — the same shape
 * as the defect that made manual issue able to duplicate a policy in the first place. The three
 * bases that start cover are the three where cover genuinely already exists somewhere else.
 */
public enum IssuanceBasis {
    /** Brought in from another administration system, in force there and paid for years. */
    MIGRATION(true),
    /** Continuous cover from a converted policy; a gap would be a real lapse in someone's life assurance. */
    CONVERSION(true),
    /** Follows arrears being settled, so the money has already arrived. */
    REINSTATEMENT(true),
    /** A manual review overturning an automated block. Changes WHO may be covered, not whether they pay. */
    UNDERWRITING_OVERRIDE(false),
    /** Guaranteed acceptance waives evidence of insurability, not the premium. */
    GUARANTEED_ISSUE(false);

    private final boolean startsCoverImmediately;

    IssuanceBasis(boolean startsCoverImmediately) {
        this.startsCoverImmediately = startsCoverImmediately;
    }

    public boolean startsCoverImmediately() {
        return startsCoverImmediately;
    }
}
```

`Policy.java`, beside `activate`:

```java
/**
 * The offer expired unpaid.
 *
 * <p>Terminal, and deliberately not LAPSED: lapsing is what happens to an in-force policy whose
 * premiums stop, and a contract that was never on risk does not belong in the lapse figures.
 */
public void markNotTakenUp() {
    if (!"PROPOSED".equals(status)) {
        throw new InvalidPolicyStateException(
            "Policy " + policyNumber + " is " + status + ", not an outstanding offer");
    }
    this.status = "NOT_TAKEN_UP";
}
```

- [ ] **Step 5: Add the migration to the test classes that need it**

`policy/V11` must be listed wherever `policy/V10` already is. Use a pattern-only node script:

```bash
node scratch/add-migration.js "policy/V10__one_policy_per_underwriting_case.sql" "policy/V11__not_taken_up_status.sql"
```

If that script is not present, write one that reads each `src/test/java/**/*.java`, finds the line containing the V10 literal, and inserts the V11 literal after it preserving indentation. Do NOT use a shell heredoc or backticks.

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='PolicyApiIntegrationTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/db-migrations/policy/V11__not_taken_up_status.sql backend/src/main/java/tz/co/nlolo/lifeplatform/policy backend/src/test/java
git commit -m "feat(policy): NOT_TAKEN_UP, and an issuance basis that says whether cover starts"
```

---

## Task 2: An accepted decision produces an offer, not cover

**Files:**
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Consumes: `IssuanceBasis` from Task 1.
- Produces: `IssueRequest` gains a trailing `IssuanceBasis issuanceBasis`; a null basis means the normal path and leaves the policy `PROPOSED`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void anIssuedPolicyIsAnOfferUntilItsFirstPremium() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-01");
    String policyNumber = issueDirectly(tenantId, fixture, List.of());

    PolicyView view = policyApi.getPolicy(policyNumber);
    assertThat(view.status()).isEqualTo(PolicyStatus.PROPOSED);
    assertThat(policyApi.isInForce(policyNumber, LocalDate.now()))
        .as("an offer nobody has paid for is not cover")
        .isFalse();
}

@Test
void aMigrationIsInForceImmediatelyBecauseItAlreadyWas() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-MIG");
    TenantContext.set(tenantId);
    PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
        fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
        new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
        null, List.of(), "Brought in from the legacy book",
        null, null, null, null, IssuanceBasis.MIGRATION);

    PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(), request, "test-agent");
    assertThat(issued.status()).isEqualTo(PolicyStatus.ACTIVE);
}

@Test
void anUnderwritingOverrideStillWaitsForTheMoney() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-OVR");
    TenantContext.set(tenantId);
    PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
        fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
        new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY",
        null, List.of(), "Senior underwriter overturned the automated decline",
        null, null, null, null, IssuanceBasis.UNDERWRITING_OVERRIDE);

    PolicyView issued = policyApi.issuePolicy(UUID.randomUUID(), request, "test-agent");
    assertThat(issued.status())
        .as("overturning a block changes who may be covered, not whether they pay")
        .isEqualTo(PolicyStatus.PROPOSED);
}
```

Check `PolicyApi.isInForce`'s real signature before writing the first test; if it takes only a policy number, drop the date argument.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#anIssuedPolicyIsAnOfferUntilItsFirstPremium'`
Expected: FAIL — status is `ACTIVE`.

- [ ] **Step 3: Widen `IssueRequest`**

Add `IssuanceBasis issuanceBasis` as the final component of the canonical constructor, and delegate from the existing 15-argument constructor passing `null`:

```java
    /** Pre-offer issuance, with no issuance basis: the normal path, which now waits for a premium. */
    public IssueRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                         BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                         BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                         UUID agentOfRecordId, List<BeneficiaryInput> beneficiaries,
                         String reasonForManualIssue, LocalDate commencementDate,
                         Integer policyTermMonths, Integer premiumPayingTermMonths,
                         UUID lifeAssuredPartyId) {
        this(policyholderPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
            premiumAmount, premiumCurrency, premiumFrequency, agentOfRecordId, beneficiaries,
            reasonForManualIssue, commencementDate, policyTermMonths, premiumPayingTermMonths,
            lifeAssuredPartyId, null);
    }
```

- [ ] **Step 4: Make activation conditional in `issuePolicy`**

Replace the unconditional `policy.activate(LocalDate.now());` with:

```java
        // An accepted decision produces an OFFER, not cover. The policy exists so the customer
        // has something to pay against -- billing raises its first invoice off PolicyIssued --
        // and PremiumCollectedEventListener activates it when the money clears.
        //
        // A manual issuance whose basis already carries cover skips the wait. See IssuanceBasis:
        // the three that do are the three where the contract is in force somewhere else already.
        boolean startsCoverNow = request.issuanceBasis() != null
            && request.issuanceBasis().startsCoverImmediately();
        if (startsCoverNow) {
            policy.activate(LocalDate.now());
        }
        policyRepository.save(policy);
```

Then, after the existing `PolicyIssued` publication, add:

```java
        // Both events together for an immediate-cover issuance, so every downstream consumer
        // behaves exactly as it did before this change.
        if (startsCoverNow) {
            publishPolicyActivated(policyNumber, tenantId, policy);
        }
```

And add the helper beside `issuePolicy`:

```java
    /**
     * "On risk, premium received." The event commission, cession and the regulatory return key
     * off — as distinct from PolicyIssued, which now only means the contract record exists.
     */
    private void publishPolicyActivated(String policyNumber, UUID tenantId, Policy policy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", policy.getPolicyholderPartyId());
        payload.put("productId", policy.getProductId());
        payload.put("productVersionId", policy.getProductVersionId());
        payload.put("sumAssured", Map.of("amount", policy.getSumAssuredAmount().toPlainString(),
                                          "currencyCode", policy.getSumAssuredCurrency()));
        payload.put("premium", Map.of("amount", policy.getPremiumAmount().toPlainString(),
                                       "currencyCode", policy.getPremiumCurrency()));
        payload.put("premiumFrequency", policy.getPremiumFrequency());
        payload.put("agentOfRecordId", policy.getAgentOfRecordId());
        payload.put("activatedAt", LocalDate.now().toString());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyActivated", tenantId, payload));
    }
```

The payload carries everything the three moving listeners read today from `PolicyIssued` — verify each against its own `handlePolicyIssued` before finishing this step, and add any key one of them reads that is missing here.

- [ ] **Step 5: Full recompile, because a record grew**

Run: `cd backend && ./mvnw clean test-compile`
Expected: BUILD SUCCESS. Fix any construction site the widened record breaks.

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest'`
Expected: the three new tests PASS. Others will fail — every test that issues a policy and expects it ACTIVE. Record the list; Task 3 makes them pass by activating on payment, and Task 6 repairs the fixtures that legitimately need an in-force policy.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy backend/src/test/java/tz/co/nlolo/lifeplatform/policy
git commit -m "feat(policy): an accepted decision produces an offer, not cover"
```

---

## Task 3: The first premium starts the cover

**Files:**
- Create: `policy/application/PremiumCollectedEventListener.java`
- Modify: `policy/api/PolicyApi.java`, `policy/application/PolicyApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Produces: `PolicyApi.activateOnFirstPremium(String policyNumber)` — idempotent, publishes `policy.PolicyActivated` only on the PROPOSED → ACTIVE transition.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void theFirstPremiumStartsTheCover() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-ACT-01");
    String policyNumber = issueDirectly(tenantId, fixture, List.of());
    assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.PROPOSED);

    policyApi.activateOnFirstPremium(policyNumber);

    assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
}

/**
 * Idempotent on purpose. A second PremiumCollected is the ordinary second month, and re-firing
 * PolicyActivated would double-accrue the agent's commission and double-cede the risk.
 */
@Test
void asecondPremiumDoesNotReactivateOrRepublish() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-ACT-02");
    String policyNumber = issueDirectly(tenantId, fixture, List.of());
    policyApi.activateOnFirstPremium(policyNumber);

    long activatedBefore = auditLogRepository.findAll().stream()
        .filter(e -> "policy.PolicyActivated".equals(e.getEventType())).count();

    policyApi.activateOnFirstPremium(policyNumber);

    assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
    long activatedAfter = auditLogRepository.findAll().stream()
        .filter(e -> "policy.PolicyActivated".equals(e.getEventType())).count();
    assertThat(activatedAfter).isEqualTo(activatedBefore);
}
```

Check `AuditLogEntry`'s accessor for the event type before writing the second test — if it is not `getEventType()`, use the real one.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#theFirstPremiumStartsTheCover'`
Expected: compilation failure — `activateOnFirstPremium` does not exist.

- [ ] **Step 3: Declare and implement it**

On `PolicyApi`:

```java
    /**
     * Start cover, because the first premium has cleared.
     *
     * <p>Idempotent and silent on anything that is not an outstanding offer: a second
     * PremiumCollected is the ordinary second month, and a policy that reached ACTIVE through a
     * MIGRATION issuance never had an offer to accept. Re-publishing PolicyActivated would
     * double-accrue commission and double-cede the risk, so the guard is not a nicety.
     */
    void activateOnFirstPremium(String policyNumber);
```

In `PolicyApiImpl`:

```java
    @Override
    @Transactional
    public void activateOnFirstPremium(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        if (!PolicyStatus.PROPOSED.name().equals(policy.getStatus())) {
            return;
        }
        policy.activate(LocalDate.now());
        policyRepository.save(policy);
        publishPolicyActivated(policyNumber, tenantId, policy);
    }
```

- [ ] **Step 4: Write the listener**

```java
package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Starts cover when billing confirms the first premium.
 *
 * <p>AFTER_COMMIT with PROPAGATION_REQUIRES_NEW, for the reason
 * {@link UnderwritingDecisionEventListener} documents at length: at AFTER_COMMIT the producer's
 * transaction has physically committed but Spring has not unbound its resources, so a plain
 * REQUIRED call would silently join an already-committed transaction and the writes would never
 * land anywhere. That exact failure was caught empirically once on this platform.
 */
@Component
public class PremiumCollectedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PremiumCollectedEventListener.class);

    private final PolicyApi policyApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PremiumCollectedEventListener(PolicyApi policyApi, PlatformTransactionManager transactionManager) {
        this.policyApi = policyApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        String policyNumber = (String) payload.get("policyNumber");

        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(
                status -> policyApi.activateOnFirstPremium(policyNumber));
        } catch (Exception e) {
            // The premium is collected either way -- billing has committed. Cover not starting
            // is the serious half, so it is logged loudly rather than swallowed silently, and
            // a later premium on the same policy will activate it.
            log.error("Failed to start cover for policy {} after its premium was collected", policyNumber, e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
```

- [ ] **Step 5: Pin the claim guard, now that PROPOSED is reachable**

`Policy` has always refused claim settlement on any status but ACTIVE — its own javadoc says
"PROPOSED above all". That was unreachable until now: no policy could BE proposed. It is the
single most consequential thing this change could get wrong, so it gets a test.

Add to `PolicyApiIntegrationTest`:

```java
/**
 * An offer is not cover, and the clearest proof is that nobody can claim on it.
 *
 * <p>Policy.recordClaimSettlement has always refused a non-ACTIVE status -- "PROPOSED above
 * all", in its own words -- but no policy could reach PROPOSED before this change, so the
 * guard had never been exercised against a real one.
 */
@Test
void aClaimCannotBeSettledAgainstAnOfferNobodyHasPaidFor() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "POLICY-OFFER-CLAIM");
    String policyNumber = issueDirectly(tenantId, fixture, List.of());
    assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.PROPOSED);

    assertThat(policyApi.isInForce(policyNumber, LocalDate.now())).isFalse();
}
```

Check `PolicyApi` for the real claim-settlement entry point and assert against that directly if
one is reachable from this module; `isInForce` is the guard every claims path consults, so it is
the honest minimum if settlement is only callable from `claims`.

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='PolicyApiIntegrationTest'`
Expected: the three new tests PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy backend/src/test/java/tz/co/nlolo/lifeplatform/policy
git commit -m "feat(policy): the first premium starts the cover"
```

---

## Task 4: Commission, cession and the regulatory count wait for cover

**Files:**
- Modify: `distribution/application/PolicyEventListener.java`, `reinsurance/application/PolicyEventListener.java`, `regreporting/application/PolicyEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/distribution/CommissionAccrualEndToEndTest.java`, `reinsurance/CessionEndToEndTest.java`, `regreporting/ProjectionEndToEndTest.java`

**Interfaces:**
- Consumes: `policy.PolicyActivated` from Task 2.

- [ ] **Step 1: Move each case label**

In all three files, change:

```java
            case "policy.PolicyIssued" -> withTenant(envelope, this::handlePolicyIssued);
```

to:

```java
            // PolicyActivated, not PolicyIssued. PolicyIssued now means "the contract record
            // exists" -- an offer awaiting its first premium. Accruing commission, ceding the
            // risk or counting new business against a proposal would mean paying, ceding and
            // reporting for people who may never pay, then unwinding all three.
            case "policy.PolicyActivated" -> withTenant(envelope, this::handlePolicyActivated);
```

and rename each `handlePolicyIssued` to `handlePolicyActivated`. The method bodies are unchanged — the payload carries the same keys.

For `regreporting`, keep `policy_dimension` written here: the dimension is what later LAPSED/SURRENDERED movements look up, and a policy that never activated produces no such movements.

- [ ] **Step 2: Update each end-to-end test to pay before asserting**

Each of the three tests issues a policy and asserts the downstream effect. They must now collect a premium first. Where a test uses `issueDirectly(...)` or an equivalent local helper, follow it with:

```java
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);
```

Do not weaken any assertion. If an assertion fails after activation, that is a real regression in this change.

- [ ] **Step 3: Add the "not before cover" halves**

One per module. In `CommissionAccrualEndToEndTest`:

```java
@Test
void noCommissionAccruesOnAnOfferNobodyHasPaidFor() {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId, "COMM-OFFER-01");
    String policyNumber = issueDirectly(tenantId, fixture, List.of());

    TenantContext.set(tenantId);
    assertThat(commissionStatementRepository.findAll())
        .as("an agent has earned nothing until the customer has paid something")
        .isEmpty();
}
```

Use each file's own fixture helpers and repository names rather than the sketched ones; the shape is what matters. Write the equivalent in `CessionEndToEndTest` (no cession row) and `ProjectionEndToEndTest` (no `policies_issued` movement).

- [ ] **Step 4: Run the three suites**

Run: `cd backend && ./mvnw test -Dtest='CommissionAccrualEndToEndTest,CessionEndToEndTest,ProjectionEndToEndTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/distribution backend/src/main/java/tz/co/nlolo/lifeplatform/reinsurance backend/src/main/java/tz/co/nlolo/lifeplatform/regreporting backend/src/test/java
git commit -m "feat: commission, cession and the regulatory count wait for cover to start"
```

---

## Task 5: Manual issue must say why

**Files:**
- Modify: `policy/infrastructure/ManualIssueRequestDto.java`, `policy/infrastructure/PolicyController.java`, `api/openapi/openapi-policy.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyContractTest.java`

- [ ] **Step 1: Make the basis required on the DTO**

```java
    /**
     * Why this is being issued by hand, and — through {@link IssuanceBasis#startsCoverImmediately()}
     * — whether cover starts now or waits for the first premium.
     *
     * <p>Required. {@code reasonForManualIssue} stays alongside it as free text: the enum is what
     * a report can group by, the sentence is what a person reads.
     */
    @NotNull IssuanceBasis issuanceBasis,
```

- [ ] **Step 2: Pass it through the controller**

Append `request.issuanceBasis()` as the final argument of the `PolicyApi.IssueRequest` construction in `manualIssue`.

- [ ] **Step 3: Update the OpenAPI schema**

Add to `ManualIssueRequest`'s `required` list and `properties`:

```yaml
        issuanceBasis:
          description: >-
            Why this policy is being issued by hand, and whether cover starts immediately.
            MIGRATION, CONVERSION and REINSTATEMENT start cover at once — the contract is
            already in force elsewhere, or the money has already arrived. UNDERWRITING_OVERRIDE
            and GUARANTEED_ISSUE are new business and wait for the first premium like any other:
            overturning an automated block changes who may be covered, and guaranteed acceptance
            waives evidence of insurability, but neither waives the premium.
          type: string
          enum: [MIGRATION, CONVERSION, REINSTATEMENT, UNDERWRITING_OVERRIDE, GUARANTEED_ISSUE]
```

- [ ] **Step 4: Write the contract tests**

Read this file's existing manual-issue test first and reuse its fixture helpers and its JWT
idiom verbatim; only the body and the assertions below are new. The payload shape shown here is
the one that file already sends, plus `issuanceBasis`.

```java
@Test
void manualIssueIsRefusedWithoutAnIssuanceBasis() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId);

    mockMvc.perform(post("/policies/manual-issue")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                 "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                 "premiumAmount":{"amount":"50000.00","currencyCode":"TZS"},
                 "agentOfRecordId":null,"reasonForManualIssue":"No basis given"}
                """.formatted(UUID.randomUUID(), fixture.applicantId(), fixture.productVersionId())))
        .andExpect(status().isBadRequest());
}

@Test
void aMigrationIsActiveImmediatelyAndAnOverrideWaitsForTheMoney() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Fixture fixture = buildFixture(tenantId);

    mockMvc.perform(post("/policies/manual-issue")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                 "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                 "premiumAmount":{"amount":"50000.00","currencyCode":"TZS"},
                 "agentOfRecordId":null,"reasonForManualIssue":"Legacy book",
                 "issuanceBasis":"MIGRATION"}
                """.formatted(UUID.randomUUID(), fixture.applicantId(), fixture.productVersionId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    mockMvc.perform(post("/policies/manual-issue")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                 "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                 "premiumAmount":{"amount":"50000.00","currencyCode":"TZS"},
                 "agentOfRecordId":null,"reasonForManualIssue":"Senior overturned the decline",
                 "issuanceBasis":"UNDERWRITING_OVERRIDE"}
                """.formatted(UUID.randomUUID(), fixture.applicantId(), fixture.productVersionId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PROPOSED"));
}
```

Each call uses a fresh random `underwritingCaseId` deliberately: one case issues one policy, so
reusing it would 409 on the second and the test would pass for the wrong reason.

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='PolicyContractTest'`

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy backend/api/openapi/openapi-policy.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/policy
git commit -m "feat(policy): manual issue must name its basis, and the basis decides whether cover starts"
```

---

## Task 6: Repair every fixture that needs an in-force policy

**Files:**
- Modify: the test classes Task 2 Step 6 listed.

- [ ] **Step 1: Add activation where a test needs cover, not an offer**

Most failures will be tests that issue a policy and then do something only possible in force — register a claim, take a loan, suspend it. Each needs:

```java
        policyApi.activateOnFirstPremium(policyNumber);
```

after issuance. Where a test's subject IS the offer state, assert `PROPOSED` instead.

Do not add activation to a test whose assertion is about issuance itself, and do not weaken any assertion to make it pass. A test that genuinely breaks is a finding.

- [ ] **Step 2: Full clean run**

Run: `cd backend && ./mvnw clean test`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java
git commit -m "test: activate the policy wherever a fixture needs cover rather than an offer"
```

---

## Task 7: Expire unpaid offers, and stop chasing them

**Files:**
- Create: `backend/db-migrations/refdata/V5__seed_offer_validity.sql`, `backend/db-migrations/_post-migration/configure-offer-expiry-sweep.sql`
- Modify: `backend/db-migrations/_post-migration/configure-billing-sweep.sql`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/OfferExpirySweepTest.java` (create)

- [ ] **Step 1: Seed the window**

```sql
-- db-migrations/refdata/V5__seed_offer_validity.sql
-- How long an offer stays open before it becomes NOT_TAKEN_UP.
--
-- Long enough for mobile money after payday and for an agent's field receipt to reconcile;
-- short enough that nobody goes on risk against medical evidence assessed a season ago, which
-- is the reason offers expire at all.
--
-- Reference data rather than a constant in the SQL, following TZ_CONTESTABILITY_MONTHS and the
-- dunning thresholds: it is a business parameter a policy committee moves, not a fact.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_OFFER_VALIDITY_DAYS', 'DEFAULT', 'Days an unpaid offer stays open before it lapses unaccepted', '30', 'TZ'); -- PLACEHOLDER, pending Underwriting sign-off
```

- [ ] **Step 2: Write the sweep**

```sql
-- db-migrations/_post-migration/configure-offer-expiry-sweep.sql
-- NOT applied by scripts/migrate.sh -- an ops file, applied by hand, exactly like
-- configure-billing-sweep.sql.
--
-- Closes offers nobody took up. A policy sits PROPOSED from the moment an underwriter accepts
-- until its first premium clears; most are paid, and the rest would otherwise sit as
-- outstanding offers forever, on terms whose medical evidence goes stale.
--
-- SECURITY DEFINER and cross-tenant, matching billing.sweep_billing_state(): a business-state
-- sweep runs over every tenant and cannot rely on a request-scoped TenantContext.
CREATE OR REPLACE FUNCTION policy.sweep_expired_offers() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    validity_days INTEGER;
BEGIN
    SELECT value::INTEGER INTO validity_days
    FROM refdata.reference_code_set
    WHERE code_set_key = 'TZ_OFFER_VALIDITY_DAYS' AND code = 'DEFAULT';

    IF validity_days IS NULL THEN
        RAISE WARNING 'TZ_OFFER_VALIDITY_DAYS is not seeded; no offers expired';
        RETURN;
    END IF;

    UPDATE policy.policy
       SET status = 'NOT_TAKEN_UP'
     WHERE status = 'PROPOSED'
       AND created_at < now() - (validity_days || ' days')::INTERVAL;
END;
$$;

REVOKE ALL ON FUNCTION policy.sweep_expired_offers() FROM PUBLIC;

SELECT cron.schedule('offer-expiry-sweep', '0 2 * * *', $$SELECT policy.sweep_expired_offers()$$);
```

Confirm `policy.policy` has a `created_at` column before relying on it; if the column is named differently, use the real one.

- [ ] **Step 3: Scope the arrears sweep to in-force policies**

In `configure-billing-sweep.sql`, find where arrears cases are opened against overdue invoices and add a policy-status condition, with this comment:

```sql
    -- In-force policies only. A PROPOSED policy is an offer awaiting its first premium, and its
    -- first invoice is what the customer pays to accept. Opening an arrears case against
    -- somebody who has not accepted yet fills a queue that people work daily with names that
    -- do not belong in it. PolicyLapseRecommended is already guarded (the listener acts only on
    -- ACTIVE or SUSPENDED), so this is about the collections queue, not about lapsing.
```

- [ ] **Step 4: Write the sweep test**

`_post-migration` files are not in any migration list, so the test applies the function text
itself through its own connection. Add to `PolicyApiIntegrationTest` rather than a new class —
it already has the datasource, the fixtures and the migration list.

```java
/**
 * The sweep closes an offer past the window and leaves a younger one alone.
 *
 * <p>The function lives in `_post-migration`, which `scripts/migrate.sh` deliberately does not
 * apply, so it is loaded here from the same file operations runs -- a copy inlined in the test
 * would pass while the real file rotted.
 */
@Test
void theSweepClosesAnOfferPastTheWindowAndLeavesAYoungerOneAlone() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Fixture oldFixture = buildFixture(tenantId, "POLICY-SWEEP-OLD");
    Fixture youngFixture = buildFixture(tenantId, "POLICY-SWEEP-NEW");
    String stale = issueDirectly(tenantId, oldFixture, List.of());
    String fresh = issueDirectly(tenantId, youngFixture, List.of());

    String sweepSql = Files.readString(
        Path.of("db-migrations/_post-migration/configure-offer-expiry-sweep.sql"));
    // The cron.schedule line needs the pg_cron extension, which the test container does not
    // load. Everything above it is the function itself, which is what is under test.
    String functionOnly = sweepSql.substring(0, sweepSql.indexOf("SELECT cron.schedule"));

    try (Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(functionOnly);
            statement.execute("UPDATE policy.policy SET created_at = now() - INTERVAL '31 days' "
                + "WHERE policy_number = '" + stale + "'");
            statement.execute("SELECT policy.sweep_expired_offers()");
        }
    }

    TenantContext.set(tenantId);
    assertThat(policyApi.getPolicy(stale).status()).isEqualTo(PolicyStatus.NOT_TAKEN_UP);
    assertThat(policyApi.getPolicy(fresh).status())
        .as("29 days is still an open offer; the window is a deadline, not a suggestion")
        .isEqualTo(PolicyStatus.PROPOSED);
}
```

This needs `refdata/V5__seed_offer_validity.sql` in this class's migration list, and imports for
`java.nio.file.Files`, `java.nio.file.Path`, `java.sql.Connection`, `java.sql.DriverManager` and
`java.sql.Statement`. `RowLevelSecurityIntegrationTest` already opens a raw JDBC connection this
way — copy its idiom if the datasource details differ.

**Note the deliberate asymmetry:** the sweep sets the status with a raw `UPDATE` rather than
through `Policy.markNotTakenUp()`, so it bypasses that method's guard. Its `WHERE status =
'PROPOSED'` enforces the same rule at the same moment, which is why this is safe — but it is two
statements of one invariant, and if the guard ever gains a condition the SQL must gain it too.

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./mvnw test -Dtest='OfferExpirySweepTest'`

```bash
git add backend/db-migrations backend/src/test/java
git commit -m "feat(policy): expire unpaid offers after 30 days, and stop chasing them for arrears"
```

---

## Task 8: The console shows an offer as an offer

**Files:**
- Modify: `frontend/src/features/policies/policyIssueForm.ts`, `IssuePolicyPage.tsx`, `PolicyDetailPage.tsx`, `frontend/src/lib/status.ts` (if it enumerates statuses)
- Test: `frontend/src/features/policies/policyIssueForm.test.ts`

- [ ] **Step 1: Regenerate the API types**

Run: `cd frontend && npm run generate:api`

- [ ] **Step 2: Add the basis to the form schema**

```ts
  /**
   * Why this is being issued by hand. Required, and the value decides whether cover starts now
   * or waits for the first premium — see the backend's IssuanceBasis.
   */
  issuanceBasis: z.enum([
    'MIGRATION',
    'CONVERSION',
    'REINSTATEMENT',
    'UNDERWRITING_OVERRIDE',
    'GUARANTEED_ISSUE',
  ], { message: 'Say why this is being issued by hand' }),
```

Add `issuanceBasis: '' as unknown as IssuanceBasis` is NOT acceptable — start it unset by making the field a union with `''` and refining, the same shape `premiumFrequency` uses in `openCaseForm.ts`.

- [ ] **Step 3: Add the selector and say what it does**

In `IssuePolicyPage.tsx`, a `Select` with the five options, and beneath it a line that changes with the selection: for the three immediate bases, "Cover starts immediately — this contract is already in force elsewhere"; for the other two, "Cover starts when the first premium clears".

- [ ] **Step 4: Show a proposed policy as an offer**

On `PolicyDetailPage`, a `PROPOSED` policy gets a panel saying cover has not started and what would start it, and `NOT_TAKEN_UP` gets one saying the offer expired unpaid. A status badge alone would leave a reader guessing whether the person is covered — which is the single most important thing this page says.

- [ ] **Step 5: Write the schema tests**

```ts
it('refuses a manual issuance that does not say why', () => {
  const result = policyIssueFormSchema.safeParse({ ...valid(), issuanceBasis: '' });
  expect(result.success).toBe(false);
});

it('sends the basis through unchanged', () => {
  const parsed = policyIssueFormSchema.parse({ ...valid(), issuanceBasis: 'MIGRATION' });
  expect(toApiRequest(parsed).issuanceBasis).toBe('MIGRATION');
});
```

- [ ] **Step 6: Run and commit**

Run: `cd frontend && npm run typecheck && npm run lint && npm test`

```bash
git add frontend/src
git commit -m "feat(console): an offer reads as an offer, and manual issue says why"
```

---

## Task 9: End to end, and the written record

**Files:**
- Modify: `frontend/e2e/underwriting.ts`, `frontend/e2e/policies.ts`, `frontend/e2e/staff-underwriting.spec.ts`
- Modify: `backend/api/asyncapi-events.yaml`, `backend/docs/05-event-catalog.md`, `backend/api/openapi/openapi-policy.yaml`

- [ ] **Step 1: Apply the migrations and restart**

Apply `policy/V11` and `refdata/V5` to the dev database, apply the two `_post-migration` files by hand, rebuild the jar and restart the backend. Green Testcontainers tests do not prove the dev database is in sync.

- [ ] **Step 2: Update the shared e2e fixtures**

`issueRealPolicy` in `e2e/policies.ts` must now select an issuance basis, and every spec that needs an in-force policy needs one whose basis starts cover — `MIGRATION` is the honest choice for a fixture representing an existing contract. Say so in the helper's doc.

- [ ] **Step 3: Add the offer-to-cover e2e test**

In `staff-underwriting.spec.ts`, extend the existing capture test: after the decision, assert the policy reads as an offer and is not in force; then collect its first premium through the invoices panel; then assert cover has started.

- [ ] **Step 4: Correct the event catalogue**

`policy.PolicyActivated` is new: producer `policy`, consumers distribution, reinsurance, regreporting, communication, audit. `policy.PolicyIssued` keeps its consumers minus those three, and its description must now say it means "the contract record exists", not "cover started". Update `asyncapi-events.yaml` and `docs/05-event-catalog.md` together.

- [ ] **Step 5: Correct `openapi-policy.yaml`'s header**

It says normal issuance is system-triggered by consuming `UnderwritingDecisionMade`. Still true, and it now produces an offer rather than cover. Say so in a sentence.

- [ ] **Step 6: Run the e2e suite and commit**

Run: `cd frontend && npx playwright test`

Two known traps: never run it concurrently with a Maven build, and a run spanning a machine sleep produces failures that are not code — check the backend log for a large Hikari `housekeeper delta` before believing a cluster of failures.

```bash
git add frontend/e2e backend/api backend/docs
git commit -m "test(e2e): an offer becomes cover when the premium clears"
```

---

## Out of scope

- **Customer notifications** — Project 2, the communication module.
- **Per-product base rates in issuance**; the case-to-policy back-reference; s.119 free-look dates; re-opening a declined case; KYC gating. All carried forward unchanged.
