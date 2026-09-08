# Underwriting Decision and Single Issuance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate assessing from deciding so a human underwriter makes the underwriting decision, stop the automatic issuance path discarding data the case already holds, and make two policies from one underwriting case impossible.

**Architecture:** `submitAssessment` stops deciding and instead stores the rules engine's output as a *recommendation*. A new `decide` call records a human decision, and only that publishes `UnderwritingDecisionMade`. Deviating from the recommendation requires the caller to hold `SENIOR_UNDERWRITER`. On the policy side, `issuePolicy` gains a duplicate guard backed by a partial unique index, and the auto-issue listener stops dropping the life assured and the proposed commencement date.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Flyway (per-module migration directories), Postgres 16, Testcontainers, JUnit 5. Frontend: React + TypeScript, Zustand, zod, react-hook-form, Vitest, Playwright.

## Global Constraints

- **Never run Maven in Docker.** Use `./mvnw` on the host with the VS Code JDK. Testcontainers networking breaks otherwise.
- **After any record/DTO/interface signature change, run `./mvnw clean test-compile`.** Maven's incremental compile does not recompile unchanged tests against a widened record, so a scoped run stays green while `clean` surfaces real breaks.
- **Never run Prettier.** Not a dependency here, no config; it reformats the whole tree.
- **Migrations live in `backend/db-migrations/<module>/`,** not under `src/main/resources`. Next free numbers: `underwriting/V5`, `policy/V10`.
- **The dev backend and its migrations are decoupled.** A new migration needs a manual `psql` apply and a server restart before real-stack e2e reflects it. Green Testcontainers tests do not prove the dev DB is in sync.
- **Mirror any endpoint tightening into `backend/scripts/seed-dev-data.sh`.** The "Already seeded" guard means nothing re-runs it, so it rots silently.
- **No `default` interface methods on `@Transactional` API interfaces.** Self-invocation skips the proxy and the AFTER_COMMIT events never fire. Declare abstract methods and implement them.
- **Spring Modulith `allowedDependencies` uses the `module::api` form.**
- Frontend lint bans synchronous `setState` in a `useEffect` body — derive rendered state instead of storing it.
- Generated request types from `openapi-typescript` make a `default:` property REQUIRED. Regenerate with `npm run generate:api` after any OpenAPI change.

---

## File Structure

**Backend — underwriting**
- `backend/db-migrations/underwriting/V5__explicit_decision.sql` (create) — recommendation columns, `decision_decided_by`, override flag.
- `underwriting/domain/UnderwritingCase.java` (modify) — recommendation fields, widened `recordDecision`.
- `underwriting/api/UnderwritingApi.java` (modify) — `decide`, `DecisionInput`.
- `underwriting/api/UnderwritingCaseView.java` (modify) — expose the recommendation and who decided.
- `underwriting/api/SeniorUnderwriterApprovalRequiredException.java` (create).
- `underwriting/application/UnderwritingApiImpl.java` (modify) — `submitAssessment` recommends; `decide` decides and publishes.
- `underwriting/infrastructure/DecideRequest.java` (create) — wire DTO.
- `underwriting/infrastructure/UnderwritingController.java` (modify) — `POST /cases/{caseId}/decision`.
- `underwriting/infrastructure/UnderwritingExceptionHandler.java` (modify) — 403 mapping.

**Backend — policy**
- `backend/db-migrations/policy/V10__one_policy_per_underwriting_case.sql` (create).
- `policy/api/PolicyAlreadyIssuedForCaseException.java` (create).
- `policy/infrastructure/PolicyRepository.java` (modify) — finder by case id.
- `policy/application/PolicyApiImpl.java` (modify) — duplicate guard in `issuePolicy`.
- `policy/infrastructure/PolicyExceptionHandler.java` (modify) — 409 mapping.
- `policy/application/UnderwritingDecisionEventListener.java` (modify) — pass life assured + commencement date.

**Backend — spec, config, seed**
- `backend/api/openapi/openapi-underwriting.yaml`, `openapi-policy.yaml` (modify).
- `backend/keycloak/staff-realm.json` (modify) — `SENIOR_UNDERWRITER` role + `staff.senior` user.
- `backend/scripts/seed-dev-data.sh` (modify) — decide after assessing.

**Frontend**
- `src/auth/claims.ts` (modify) — `SENIOR_UNDERWRITER` in `StaffRoles`.
- `src/api/underwriting.ts`, `src/store/underwritingStore.ts` (modify) — `decide`.
- `src/features/underwriting/decideForm.ts` (create) + `decideForm.test.ts` (create).
- `src/features/underwriting/DecisionPanel.tsx` (create) + `DecisionPanel.test.tsx` (create).
- `src/features/underwriting/UnderwritingCaseDetailPage.tsx` (modify) — mount the panel.
- `src/features/policies/policyIssueForm.ts` (modify) — real case id, no `crypto.randomUUID()`.
- `src/features/policies/IssuePolicyPage.tsx` (modify) — case picker.
- `e2e/staff-underwriting-queue.spec.ts`, `e2e/staff-policy-lifecycle.spec.ts`, `e2e/staff-distribution.spec.ts`, `e2e/staff-group-schemes.spec.ts` (modify).

---

## Task 1: Persist the recommendation and who decided

**Files:**
- Create: `backend/db-migrations/underwriting/V5__explicit_decision.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/domain/UnderwritingCase.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiIntegrationTest.java`

**Interfaces:**
- Produces: `UnderwritingCase.recordRecommendation(String outcome, BigDecimal loadingPercent, String reason)`; `UnderwritingCase.recordDecision(String outcome, BigDecimal loadingPercent, String declineReason, String decidedBy, boolean overrodeRecommendation)`; getters `getRecommendationOutcome()`, `getRecommendationLoadingPercent()`, `getRecommendationReason()`, `getRecommendationAt()`, `getDecisionDecidedBy()`, `isDecisionOverrodeRecommendation()`.

- [ ] **Step 1: Write the migration**

Create `backend/db-migrations/underwriting/V5__explicit_decision.sql`:

```sql
-- db-migrations/underwriting/V5__explicit_decision.sql
-- Splits "what the engine suggests" from "what a person decided".
--
-- Until now submitAssessment ran SimpleRulesEngine and wrote its output straight into the
-- decision_* columns -- an algorithm whose own comment says its thresholds are "illustrative,
-- not actuarially validated" was the sole author of every underwriting decision on the
-- platform, and an accepted decision issued a real policy. There was no decide endpoint, no
-- override, and decision_decided_by did not exist: no decision on this platform recorded who
-- made it.
--
-- The recommendation_* columns hold the engine's output. The decision_* columns keep their
-- meaning but are now written only by an explicit human decision, which also records its
-- author and whether it departed from the recommendation.
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN recommendation_outcome         VARCHAR(10)
        CHECK (recommendation_outcome IN ('ACCEPT','LOADED','DECLINED','POSTPONED')),
    ADD COLUMN recommendation_loading_percent NUMERIC(5,2),
    ADD COLUMN recommendation_reason          VARCHAR(500),
    ADD COLUMN recommendation_at              TIMESTAMPTZ,
    ADD COLUMN decision_decided_by            VARCHAR(100),
    ADD COLUMN decision_overrode_recommendation BOOLEAN NOT NULL DEFAULT FALSE;

-- Deliberately no backfill of decision_decided_by. Every case decided before this migration
-- was decided by the engine with no human involved, and inventing a name for that would
-- assert a fact that is not true. NULL here means exactly "decided before decisions had
-- authors", and readers must render it as such rather than as an unknown person.
COMMENT ON COLUMN underwriting.underwriting_case.decision_decided_by IS
    'Staff subject who made the decision. NULL for pre-V5 cases decided by the rules engine alone.';

COMMENT ON COLUMN underwriting.underwriting_case.recommendation_outcome IS
    'What SimpleRulesEngine suggested. Advisory only -- the decision_* columns are authoritative.';
```

- [ ] **Step 2: Write the failing test**

Add to `UnderwritingApiIntegrationTest`:

```java
@Test
void submittingAnAssessmentRecordsARecommendationRatherThanADecision() {
    UnderwritingCaseView opened = productAndCase();

    UnderwritingCaseView afterAssessment = underwritingApi.submitAssessment(
        opened.caseId(), AssessmentType.MEDICAL, "Standard health", new BigDecimal("10"), "uw@nlolo.co.tz");

    assertEquals(UnderwritingCaseStatus.IN_REVIEW, afterAssessment.status(),
        "an assessment is evidence; it does not settle the case");
    assertNull(afterAssessment.decisionOutcome(), "no human has decided yet");
    assertEquals(DecisionOutcome.ACCEPT, afterAssessment.recommendationOutcome(),
        "the engine's opinion is recorded, as advice");
}
```

`productAndCase()` is the existing private helper in this file that publishes a product version and opens a case; reuse it exactly as the neighbouring tests do.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest='UnderwritingApiIntegrationTest#submittingAnAssessmentRecordsARecommendationRatherThanADecision'`
Expected: compilation failure — `recommendationOutcome()` is not a member of `UnderwritingCaseView`.

- [ ] **Step 4: Add the domain fields and mutators**

In `UnderwritingCase.java`, add fields beside the existing `decision*` block:

```java
@Column(name = "recommendation_outcome")
private String recommendationOutcome;

@Column(name = "recommendation_loading_percent")
private BigDecimal recommendationLoadingPercent;

@Column(name = "recommendation_reason")
private String recommendationReason;

@Column(name = "recommendation_at")
private Instant recommendationAt;

@Column(name = "decision_decided_by")
private String decisionDecidedBy;

@Column(name = "decision_overrode_recommendation")
private boolean decisionOverrodeRecommendation;
```

Replace `recordDecision` and add `recordRecommendation`:

```java
/**
 * The engine's opinion. Advisory: it never moves the case to DECIDED, and a case may be
 * re-assessed and re-recommended any number of times before a person decides.
 */
public void recordRecommendation(String outcome, BigDecimal loadingPercent, String reason) {
    this.recommendationOutcome = outcome;
    this.recommendationLoadingPercent = loadingPercent;
    this.recommendationReason = reason;
    this.recommendationAt = Instant.now();
}

/**
 * A person's decision. The only thing that moves a case to DECIDED, and the only thing
 * downstream issuance reacts to.
 *
 * @param overrodeRecommendation whether this departed from {@link #getRecommendationOutcome()}.
 *     Stored rather than derived, because the recommendation can be recomputed by a later
 *     assessment and the fact that a human once disagreed must not be rewritten by it.
 */
public void recordDecision(String outcome, BigDecimal loadingPercent, String declineReason,
                            String decidedBy, boolean overrodeRecommendation) {
    this.decisionOutcome = outcome;
    this.decisionLoadingPercent = loadingPercent;
    this.decisionDeclineReason = declineReason;
    this.decisionDecidedAt = Instant.now();
    this.decisionDecidedBy = decidedBy;
    this.decisionOverrodeRecommendation = overrodeRecommendation;
    this.status = "DECIDED";
}

public String getRecommendationOutcome() { return recommendationOutcome; }
public BigDecimal getRecommendationLoadingPercent() { return recommendationLoadingPercent; }
public String getRecommendationReason() { return recommendationReason; }
public Instant getRecommendationAt() { return recommendationAt; }
public String getDecisionDecidedBy() { return decisionDecidedBy; }
public boolean isDecisionOverrodeRecommendation() { return decisionOverrodeRecommendation; }
```

- [ ] **Step 5: Widen `UnderwritingCaseView`**

Append to the record's parameter list, after `proposedCommencementDate`:

```java
DecisionOutcome recommendationOutcome, BigDecimal recommendationLoadingPercent,
String recommendationReason, String decisionDecidedBy, boolean decisionOverrodeRecommendation
```

`openapi-underwriting.yaml`'s `UnderwritingCaseView` declares `additionalProperties: false`, so add all five to that schema in the same step or `UnderwritingContractTest` will fail with a real `OpenApiValidationException`:

```yaml
        recommendationOutcome: { type: [string, "null"], enum: [ACCEPT, LOADED, DECLINED, POSTPONED, null] }
        recommendationLoadingPercent: { type: [number, "null"] }
        recommendationReason: { type: [string, "null"] }
        decisionDecidedBy: { type: [string, "null"] }
        decisionOverrodeRecommendation: { type: boolean }
```

Update `UnderwritingApiImpl.toView(...)` to pass the five new values through.

- [ ] **Step 6: Full recompile, because a record grew**

Run: `cd backend && ./mvnw clean test-compile`
Expected: BUILD SUCCESS. Any construction site of `UnderwritingCaseView` that Maven would not otherwise have recompiled surfaces here. Fix each by passing the new values.

- [ ] **Step 7: Run the test — it should still fail, now for the right reason**

Run: `cd backend && ./mvnw test -Dtest='UnderwritingApiIntegrationTest#submittingAnAssessmentRecordsARecommendationRatherThanADecision'`
Expected: FAIL — `decisionOutcome` is `ACCEPT`, not null, because `submitAssessment` still decides. Task 2 fixes that.

- [ ] **Step 8: Commit**

```bash
git add backend/db-migrations/underwriting/V5__explicit_decision.sql \
        backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting \
        backend/api/openapi/openapi-underwriting.yaml \
        backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting
git commit -m "feat(underwriting): store the engine's output as a recommendation, and record who decides"
```

---

## Task 2: An assessment recommends; it no longer decides

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiIntegrationTest.java`

**Interfaces:**
- Consumes: `recordRecommendation` from Task 1.
- Produces: `submitAssessment` leaves the case `IN_REVIEW`, writes no decision, and publishes no event.

- [ ] **Step 1: Rename and narrow the private decision helper**

In `UnderwritingApiImpl`, rename `decideIfPossible` to `recommendFromEvidence` and have it write a recommendation instead of a decision. The body's risk-profile assembly is unchanged; only the final line differs:

```java
/**
 * Runs the rules engine over every assessment on the case and records its opinion.
 *
 * <p>Was {@code decideIfPossible}, which despite the name had no condition and always
 * decided -- so the first assessment submitted settled the case whatever type it was, and a
 * case needing medical AND financial AND occupational review was decided by whichever
 * arrived first. It now recomputes advice each time evidence arrives, and a person decides.
 */
private void recommendFromEvidence(UnderwritingCase underwritingCase) {
    String sumAssuredBand = resolveSumAssuredBand(underwritingCase.getSumAssuredAmount());
    BigDecimal ageMultiplier = resolveAgeMultiplier(underwritingCase);
    BigDecimal sumAssuredMultiplier = productApi.resolveRatingMultiplier(
        underwritingCase.getProductVersionId(), FactorType.SUM_ASSURED_BAND, sumAssuredBand);
    List<BigDecimal> riskScores = latestScorePerAssessmentType(underwritingCase.getCaseId());

    RiskProfile profile = new RiskProfile(ageMultiplier, sumAssuredMultiplier, riskScores);
    UnderwritingDecision recommendation = rulesEnginePort.evaluate(profile);

    underwritingCase.recordRecommendation(
        recommendation.outcome().name(), recommendation.loadingPercent(), recommendation.reason());
}
```

- [ ] **Step 2: Strip the decision and the event out of `submitAssessment`**

Replace the guard, the `decideIfPossible` call, and the whole `if (isDecided(...))` event block with:

```java
    // A decided case is closed to further evidence. POSTPONED is not a decision in that
    // sense -- it means "come back with more" -- so it stays open, which is what the guard
    // has always intended.
    if (isDecided(underwritingCase) && !DecisionOutcome.POSTPONED.name().equals(underwritingCase.getDecisionOutcome())) {
        throw new UnderwritingCaseAlreadyDecidedException(caseId);
    }
    underwritingCase.markInReview();

    RiskAssessment assessment = new RiskAssessment(tenantId, caseId, assessmentType.name(), assessedBy, findings, riskScore);
    riskAssessmentRepository.save(assessment);

    // Advice only. UnderwritingDecisionMade is published by decide(...) and nowhere else --
    // an assessment must never put a policy in force, which is exactly what it used to do.
    recommendFromEvidence(underwritingCase);
    underwritingCaseRepository.save(underwritingCase);
    return toView(underwritingCase);
```

- [ ] **Step 3: Run the Task 1 test — it must now pass**

Run: `cd backend && ./mvnw test -Dtest='UnderwritingApiIntegrationTest#submittingAnAssessmentRecordsARecommendationRatherThanADecision'`
Expected: PASS.

- [ ] **Step 4: Run the full suite to see the blast radius**

Run: `cd backend && ./mvnw clean test`
Expected: FAIL. 71 call sites across 13 test files use `submitAssessment` — most as a fixture meaning "and now a policy exists". Record the failing list; Task 4 repairs them once `decide` exists. Do not repair them yet and do not weaken any assertion to make it pass.

- [ ] **Step 5: Commit the red state deliberately**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java
git commit -m "refactor(underwriting): an assessment records evidence and advice, not a decision"
```

This commit is knowingly red. Task 4 closes it, and the two are not separable without inventing a temporary decision path nobody wants.

---

## Task 3: The decision, with senior sign-off for an override

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/SeniorUnderwriterApprovalRequiredException.java`
- Modify: `underwriting/api/UnderwritingApi.java`, `underwriting/application/UnderwritingApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiIntegrationTest.java`

**Interfaces:**
- Produces: `record DecisionInput(DecisionOutcome outcome, BigDecimal loadingPercent, String reason)`; `UnderwritingCaseView decide(UUID caseId, DecisionInput decision, String decidedBy, boolean callerIsSeniorUnderwriter)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void anUnderwriterDecidesInLineWithTheRecommendation() {
    UnderwritingCaseView opened = productAndCase();
    underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw@nlolo.co.tz");

    UnderwritingCaseView decided = underwritingApi.decide(opened.caseId(),
        new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Agrees with the engine"),
        "uw@nlolo.co.tz", false);

    assertEquals(UnderwritingCaseStatus.DECIDED, decided.status());
    assertEquals(DecisionOutcome.ACCEPT, decided.decisionOutcome());
    assertEquals("uw@nlolo.co.tz", decided.decisionDecidedBy());
    assertFalse(decided.decisionOverrodeRecommendation());
}

@Test
void departingFromTheRecommendationNeedsASeniorUnderwriter() {
    UnderwritingCaseView opened = productAndCase();
    underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw@nlolo.co.tz");

    assertThrows(SeniorUnderwriterApprovalRequiredException.class, () ->
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Adverse family history off-system"),
            "uw@nlolo.co.tz", false));

    assertEquals(UnderwritingCaseStatus.IN_REVIEW, underwritingApi.getCase(opened.caseId()).status(),
        "a refused override must leave the case exactly as it was");
}

@Test
void aSeniorUnderwriterMayDepartFromTheRecommendation() {
    UnderwritingCaseView opened = productAndCase();
    underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw@nlolo.co.tz");

    UnderwritingCaseView decided = underwritingApi.decide(opened.caseId(),
        new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Adverse family history off-system"),
        "senior@nlolo.co.tz", true);

    assertEquals(DecisionOutcome.DECLINED, decided.decisionOutcome());
    assertTrue(decided.decisionOverrodeRecommendation());
}

@Test
void aDecidedCaseCannotBeDecidedTwice() {
    UnderwritingCaseView opened = productAndCase();
    underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw@nlolo.co.tz");
    underwritingApi.decide(opened.caseId(),
        new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Agreed"), "uw@nlolo.co.tz", false);

    assertThrows(UnderwritingCaseAlreadyDecidedException.class, () ->
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Again"), "uw@nlolo.co.tz", false));
}

@Test
void decidingWithNoEvidenceIsRefused() {
    UnderwritingCaseView opened = productAndCase();

    assertThrows(UnderwritingValidationException.class, () ->
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Nothing assessed"), "uw@nlolo.co.tz", false));
}

@Test
void aLoadedDecisionMustCarryALoadingAndOthersMustNot() {
    UnderwritingCaseView opened = productAndCase();
    underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw@nlolo.co.tz");

    assertThrows(UnderwritingValidationException.class, () ->
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.LOADED, null, "No figure given"), "senior@nlolo.co.tz", true));

    assertThrows(UnderwritingValidationException.class, () ->
        underwritingApi.decide(opened.caseId(),
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, new BigDecimal("25"), "Loading on a clean accept"),
            "senior@nlolo.co.tz", true));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest='UnderwritingApiIntegrationTest'`
Expected: compilation failure — `decide` and `DecisionInput` do not exist.

- [ ] **Step 3: Create the exception**

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.UUID;

/**
 * A junior underwriter tried to decide against the engine's recommendation.
 *
 * <p>Deciding in line with the recommendation is ordinary work and needs no approval.
 * Departing from it is the judgement call, and it is the one this platform had no way to
 * express at all: before the decision step existed, the engine's word was final and there
 * was nothing to override.
 */
public class SeniorUnderwriterApprovalRequiredException extends RuntimeException {
    public SeniorUnderwriterApprovalRequiredException(UUID caseId, String recommended, String attempted) {
        super("Case " + caseId + " was recommended " + recommended + " and a decision of " + attempted
            + " departs from it -- only a senior underwriter may do that");
    }
}
```

- [ ] **Step 4: Declare the API**

In `UnderwritingApi.java`:

```java
    /**
     * What a person decided, and the only thing that puts a policy in force.
     *
     * @param loadingPercent required for {@code LOADED} and forbidden otherwise, mirroring
     *     {@code chk_loading_only_when_loaded}.
     * @param reason the underwriter's own words. Required, because a decision nobody
     *     explained is one nobody can review.
     */
    record DecisionInput(DecisionOutcome outcome, BigDecimal loadingPercent, String reason) {}

    /**
     * Record a human underwriting decision and publish {@code UnderwritingDecisionMade}.
     *
     * <p>Requires at least one assessment on the case: a decision with no evidence behind it
     * is not underwriting. Requires the case not already be decided.
     *
     * <p>{@code callerIsSeniorUnderwriter} is passed in rather than read here, because the
     * caller's identity belongs to the web layer and this module must stay free of Spring
     * Security. The controller supplies it from the token's realm roles.
     *
     * @throws UnderwritingCaseAlreadyDecidedException if the case is already decided
     * @throws UnderwritingValidationException if there is no evidence, the reason is blank,
     *     or the loading does not match the outcome
     * @throws SeniorUnderwriterApprovalRequiredException if the outcome departs from the
     *     recommendation and the caller is not a senior underwriter
     */
    UnderwritingCaseView decide(UUID caseId, DecisionInput decision, String decidedBy,
                                 boolean callerIsSeniorUnderwriter);
```

- [ ] **Step 5: Implement it**

In `UnderwritingApiImpl`:

```java
    @Override
    @Transactional
    public UnderwritingCaseView decide(UUID caseId, DecisionInput decision, String decidedBy,
                                        boolean callerIsSeniorUnderwriter) {
        UUID tenantId = TenantContext.get();
        UnderwritingCase underwritingCase = findOrThrow(caseId, tenantId);

        if (isDecided(underwritingCase) && !DecisionOutcome.POSTPONED.name().equals(underwritingCase.getDecisionOutcome())) {
            throw new UnderwritingCaseAlreadyDecidedException(caseId);
        }
        if (decision.reason() == null || decision.reason().isBlank()) {
            throw new UnderwritingValidationException("A decision must carry a reason");
        }
        if (riskAssessmentRepository.countByTenantIdAndCaseId(tenantId, caseId) == 0) {
            throw new UnderwritingValidationException(
                "Case " + caseId + " has no assessment -- there is nothing to decide on");
        }
        boolean loaded = decision.outcome() == DecisionOutcome.LOADED;
        if (loaded && decision.loadingPercent() == null) {
            throw new UnderwritingValidationException("A LOADED decision must carry a loading percent");
        }
        if (!loaded && decision.loadingPercent() != null) {
            throw new UnderwritingValidationException(
                "A loading percent is only meaningful on a LOADED decision, not " + decision.outcome());
        }

        // The recommendation may legitimately be absent -- a case can be decided POSTPONED
        // and re-assessed, and a pre-V5 case has none. An absent recommendation is not a
        // disagreement, so it does not demand a senior.
        String recommended = underwritingCase.getRecommendationOutcome();
        boolean overrode = recommended != null && !recommended.equals(decision.outcome().name());
        if (overrode && !callerIsSeniorUnderwriter) {
            throw new SeniorUnderwriterApprovalRequiredException(caseId, recommended, decision.outcome().name());
        }

        String declineReason = decision.outcome() == DecisionOutcome.DECLINED
            || decision.outcome() == DecisionOutcome.POSTPONED ? decision.reason() : null;
        underwritingCase.recordDecision(decision.outcome().name(), decision.loadingPercent(),
            declineReason, decidedBy, overrode);
        underwritingCaseRepository.save(underwritingCase);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("outcome", underwritingCase.getDecisionOutcome());
        payload.put("loadingPercent", underwritingCase.getDecisionLoadingPercent());
        payload.put("decidedAt", underwritingCase.getDecisionDecidedAt().toString());
        payload.put("decidedBy", decidedBy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("underwriting.UnderwritingDecisionMade", tenantId, payload));

        return toView(underwritingCase);
    }
```

Add the counting finder to `RiskAssessmentRepository`:

```java
    long countByTenantIdAndCaseId(UUID tenantId, UUID caseId);
```

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw test -Dtest='UnderwritingApiIntegrationTest'`
Expected: the six new tests PASS. Others in the file still fail pending Task 4.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting \
        backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting
git commit -m "feat(underwriting): a person decides the case, and an override needs a senior"
```

---

## Task 4: Repair the 13 test files that used an assessment as an issuance fixture

**Files:**
- Modify: `AppRolePrivilegesIntegrationTest`, `claims/ClaimsApiIntegrationTest`, `claims/ClaimsContractTest`, `claims/ClaimSettlementEndToEndTest`, `finaccounting/ClaimAndCommissionPostingEndToEndTest`, `finaccounting/ReinsuranceAndLoanPostingEndToEndTest`, `policy/PolicyApiIntegrationTest`, `regreporting/ProjectionEndToEndTest`, `reinsurance/RecoveryEndToEndTest`, `reinsurance/ReinsuranceContractTest`, `RowLevelSecurityIntegrationTest`, `underwriting/UnderwritingApiIntegrationTest`, `underwriting/UnderwritingContractTest`

**Interfaces:**
- Consumes: `decide` from Task 3.

- [ ] **Step 1: Add the decide call after each fixture assessment**

For every site whose purpose is "and now a policy exists", follow the `submitAssessment(...)` call with:

```java
underwritingApi.decide(caseId,
    new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Fixture: standard acceptance"),
    "uw@nlolo.co.tz", false);
```

Where the fixture deliberately produces a LOADED or DECLINED outcome, pass that outcome and `true` for `callerIsSeniorUnderwriter` if it departs from what the engine recommends for that risk profile.

Do not change any assertion about the resulting policy. If an assertion now fails, that is a real regression in this change, not a fixture problem — stop and report it rather than adjusting the assertion.

- [ ] **Step 2: Update the underwriting tests that were asserting the old coupling**

In `UnderwritingApiIntegrationTest` and `UnderwritingContractTest`, any test asserting that `submitAssessment` returns a `DECIDED` case is asserting the defect. Rewrite each to assert the recommendation instead, and add a `decide` call where the test's subject is the decision.

- [ ] **Step 3: Full clean run**

Run: `cd backend && ./mvnw clean test`
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java
git commit -m "test: decide the case explicitly wherever an assessment used to issue a policy"
```

---

## Task 5: Expose the decision over HTTP

**Files:**
- Create: `underwriting/infrastructure/DecideRequest.java`
- Modify: `underwriting/infrastructure/UnderwritingController.java`, `underwriting/infrastructure/UnderwritingExceptionHandler.java`, `backend/api/openapi/openapi-underwriting.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingContractTest.java`

**Interfaces:**
- Produces: `POST /underwriting/cases/{caseId}/decision`, 200 with `UnderwritingCaseView`; 403 `SENIOR_UNDERWRITER_APPROVAL_REQUIRED`; 409 `CASE_ALREADY_DECIDED`; 422 `UNDERWRITING_VALIDATION_FAILED`.

- [ ] **Step 1: Write the wire DTO**

```java
package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;

import java.math.BigDecimal;

/**
 * {@code loadingPercent} is deliberately not annotated: it is required for LOADED and
 * forbidden otherwise, which Bean Validation cannot express on a single field. The service
 * enforces the pairing, mirroring {@code chk_loading_only_when_loaded}.
 */
public record DecideRequest(
    @NotNull DecisionOutcome outcome,
    BigDecimal loadingPercent,
    @NotBlank String reason) {}
```

- [ ] **Step 2: Add the endpoint**

```java
    /**
     * The underwriting decision.
     *
     * <p>{@code UNDERWRITER} to decide at all. Departing from the engine's recommendation
     * additionally requires {@code SENIOR_UNDERWRITER}, which is enforced in the service
     * rather than here: whether a decision IS an override depends on the case's current
     * recommendation, which no {@code @PreAuthorize} expression can see.
     */
    @PostMapping("/cases/{caseId}/decision")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingCaseView> decide(@PathVariable UUID caseId,
                                                        @Valid @RequestBody DecideRequest request,
                                                        @AuthenticationPrincipal Jwt jwt,
                                                        Authentication authentication) {
        boolean senior = authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_SENIOR_UNDERWRITER".equals(a.getAuthority()));
        return ResponseEntity.ok(underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(request.outcome(), request.loadingPercent(), request.reason()),
            jwt.getSubject(), senior));
    }
```

- [ ] **Step 3: Map the new exception**

In `UnderwritingExceptionHandler`:

```java
    @ExceptionHandler(SeniorUnderwriterApprovalRequiredException.class)
    public ProblemDetail handleSeniorApprovalRequired(SeniorUnderwriterApprovalRequiredException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage(), "SENIOR_UNDERWRITER_APPROVAL_REQUIRED");
    }
```

- [ ] **Step 4: Add the path to the OpenAPI spec**

Under `paths:` in `openapi-underwriting.yaml`:

```yaml
  /cases/{caseId}/decision:
    post:
      summary: Record the underwriting decision (underwriter; senior required to override)
      description: >-
        The human decision, and the only thing that publishes UnderwritingDecisionMade and
        therefore the only thing that puts a policy in force. Submitting an assessment records
        evidence and the rules engine's recommendation; it decides nothing.


        A decision whose outcome differs from the recorded recommendation is an override and
        requires SENIOR_UNDERWRITER in addition to UNDERWRITER. Agreeing with the
        recommendation needs only UNDERWRITER. The check is server-side and depends on the
        case's current recommendation, so it cannot be expressed as a static role gate.
      security:
        - staffAuth: []
      parameters:
        - name: caseId
          in: path
          required: true
          schema: { type: string, format: uuid }
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/DecideRequest' }
      responses:
        '200':
          description: Decided
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UnderwritingCaseView' }
        '403':
          description: "`SENIOR_UNDERWRITER_APPROVAL_REQUIRED` -- the decision departs from the recommendation"
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
        '404': { $ref: 'openapi-common.yaml#/components/responses/NotFound' }
        '409':
          description: "`CASE_ALREADY_DECIDED`"
          content:
            application/problem+json:
              schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
        '422': { $ref: 'openapi-common.yaml#/components/responses/UnprocessableEntity' }
```

And under `components/schemas:`:

```yaml
    DecideRequest:
      type: object
      additionalProperties: false
      required: [outcome, reason]
      properties:
        outcome: { type: string, enum: [ACCEPT, LOADED, DECLINED, POSTPONED] }
        loadingPercent:
          type: [number, "null"]
          description: "Required for LOADED, forbidden otherwise -- mirrors chk_loading_only_when_loaded."
        reason:
          type: string
          maxLength: 500
          description: "The underwriter's own words. A decision nobody explained is one nobody can review."
```

The contract test validates both directions against this file, so a mismatch is a real failure rather than a lint.

- [ ] **Step 5: Write the contract test**

```java
@Test
void decisionEndpointRoundTripsThroughTheSpec() throws Exception {
    UUID caseId = openCaseAndAssess();
    mockMvc.perform(post("/underwriting/cases/{caseId}/decision", caseId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"outcome":"ACCEPT","reason":"Standard risk, agrees with recommendation"}
                """)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                     new SimpleGrantedAuthority("ROLE_UNDERWRITER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DECIDED"));
}
```

Reuse this file's existing `jwt()` idiom rather than the sketch above if it differs.

- [ ] **Step 6: Run and commit**

Run: `cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='UnderwritingContractTest'`
Expected: PASS.

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting \
        backend/api/openapi/openapi-underwriting.yaml \
        backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting
git commit -m "feat(underwriting): POST /cases/{caseId}/decision, with the senior gate on overrides"
```

---

## Task 6: The SENIOR_UNDERWRITER role and a staff user who holds it

**Files:**
- Modify: `backend/keycloak/staff-realm.json`, `backend/scripts/seed-dev-data.sh`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java`

- [ ] **Step 1: Add the realm role**

In `staff-realm.json`, beside the existing `UNDERWRITER` realm role:

```json
{
  "name": "SENIOR_UNDERWRITER",
  "description": "May decide an underwriting case against the rules engine's recommendation. Additive to UNDERWRITER, never a replacement -- a senior still needs UNDERWRITER to reach the decision endpoint at all."
}
```

- [ ] **Step 2: Add `staff.senior`**

Add a user `staff.senior` with realm roles `UNDERWRITER` and `SENIOR_UNDERWRITER`, following the exact shape of the existing `staff.underwriter` entry (same credentials block, same `emailVerified`, same `enabled`).

- [ ] **Step 3: Mirror it into the seeder**

`seed-dev-data.sh` currently submits an assessment and relies on the case deciding itself. Add a decide call after it, using a senior token so the seed can produce a loaded case if the engine recommends one:

```bash
STAFF_SENIOR_TOKEN=$(token_for staff staff.senior "$STAFF_SECRET")

# The decision is now an explicit human act -- submitting an assessment only records evidence
# and the engine's advice. Seeding as staff.senior rather than staff.underwriter so the seed
# is not blocked if the engine's recommendation and the demo decision ever diverge.
api "$STAFF_SENIOR_TOKEN" POST "/underwriting/cases/$CASE_ID/decision" \
  '{"outcome":"ACCEPT","reason":"Seed data: standard risk"}' >/dev/null
```

- [ ] **Step 4: Assert the role actually gates something**

Add to `AppRolePrivilegesIntegrationTest` a case proving `UNDERWRITER` alone gets 403 on an override and `UNDERWRITER + SENIOR_UNDERWRITER` gets 200, exercised through MockMvc exactly like the file's neighbouring privilege tests.

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./mvnw test -Dtest='AppRolePrivilegesIntegrationTest'`

```bash
git add backend/keycloak/staff-realm.json backend/scripts/seed-dev-data.sh \
        backend/src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java
git commit -m "feat(auth): a SENIOR_UNDERWRITER role, and a staff.senior who holds it"
```

**Note for the operator:** the realm import only runs on a fresh Keycloak. Either `docker compose down -v` the Keycloak volume or add the role and user through the admin console before the dev stack will have them.

---

## Task 7: Stop the automatic path discarding the life assured and the commencement date

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/UnderwritingDecisionEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Consumes: the 15-argument canonical `PolicyApi.IssueRequest` constructor.

- [ ] **Step 1: Write the failing test**

```java
@Test
void automaticIssuanceCarriesTheLifeAssuredAndCommencementDateFromTheCase() {
    UUID applicant = registerParty("Neema", "Applicant");
    UUID insuredChild = registerParty("Baraka", "Child");
    LocalDate proposedStart = LocalDate.now().minusDays(3);

    UnderwritingCaseView opened = underwritingApi.openCase(applicant, productId, productVersionId,
        new BigDecimal("5000000"), "TZS", null,
        new ProposalDetails(insuredChild, "Dar HQ", "AGENCY", proposedStart), "uw@nlolo.co.tz");
    underwritingApi.submitAssessment(opened.caseId(), AssessmentType.MEDICAL, "Standard", new BigDecimal("10"), "uw@nlolo.co.tz");
    underwritingApi.decide(opened.caseId(),
        new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw@nlolo.co.tz", false);

    // searchPolicies(policyholderPartyId, relatedPartyId, status, q, agentOfRecordIds, pageable)
    // -- check the live signature in PolicyApi before writing this line; it has grown twice.
    PolicyView issued = policyApi.searchPolicies(applicant, null, null, null, null, PageRequest.of(0, 1))
        .getContent().getFirst();

    assertEquals(insuredChild, issued.lifeAssuredPartyId(),
        "a parent insuring a child must not be recorded as insuring themselves");
    assertEquals(proposedStart, issued.commencementDate(),
        "the date the proposal asked cover to start is the date it starts");
}
```

Use this file's existing party-registration and product-publishing helpers rather than the sketched `registerParty`; match the neighbouring tests exactly.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#automaticIssuanceCarriesTheLifeAssuredAndCommencementDateFromTheCase'`
Expected: FAIL — `lifeAssuredPartyId` equals the applicant, and `commencementDate` is null.

- [ ] **Step 3: Pass the two values through**

In the listener, replace the 11-argument `IssueRequest` construction with the full form:

```java
                // The case has carried lifeAssuredPartyId and proposedCommencementDate since
                // underwriting V4, and this listener used the pre-Build-2 eleven-argument
                // constructor, which fills the last four with nulls. So every automatically
                // issued policy recorded the POLICYHOLDER as the life assured -- wrong for
                // exactly the business the field was added for, credit life and group, where
                // the applicant insures somebody else -- and carried no commencement date, and
                // therefore no term and no maturity date.
                //
                // policyTermMonths and premiumPayingTermMonths stay null: nothing on a case
                // records a requested term yet. That is a capture gap, not a discard, and it
                // is not closed here.
                PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(
                    decidedCase.applicantPartyId(), decidedCase.productId(), decidedCase.productVersionId(),
                    decidedCase.sumAssuredAmount(), decidedCase.sumAssuredCurrency(),
                    monthlyPremium, decidedCase.sumAssuredCurrency(), "MONTHLY",
                    decidedCase.agentOfRecordId(), List.of(),
                    "Automatic issuance on underwriting decision " + outcome,
                    decidedCase.proposedCommencementDate(), null, null,
                    decidedCase.lifeAssuredPartyId());
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#automaticIssuanceCarriesTheLifeAssuredAndCommencementDateFromTheCase'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/UnderwritingDecisionEventListener.java \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java
git commit -m "fix(policy): automatic issuance was recording the wrong life assured and no start date"
```

---

## Task 8: One policy per underwriting case

**Files:**
- Create: `backend/db-migrations/policy/V10__one_policy_per_underwriting_case.sql`, `policy/api/PolicyAlreadyIssuedForCaseException.java`
- Modify: `policy/infrastructure/PolicyRepository.java`, `policy/application/PolicyApiImpl.java`, `policy/infrastructure/PolicyExceptionHandler.java`, `backend/api/openapi/openapi-policy.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/PolicyApiIntegrationTest.java`

**Interfaces:**
- Produces: `PolicyRepository.findByTenantIdAndUnderwritingCaseId(UUID, UUID)`; 409 `POLICY_ALREADY_ISSUED_FOR_CASE`.

- [ ] **Step 1: Check the dev database for existing duplicates first**

Run against the dev Postgres:

```sql
SELECT underwriting_case_id, count(*), array_agg(policy_number)
FROM policy.policy
WHERE underwriting_case_id IS NOT NULL
GROUP BY underwriting_case_id HAVING count(*) > 1;
```

If this returns rows, the unique index will not build. Report the rows and stop — deciding which of two live policies to retire is a business call, not a migration.

- [ ] **Step 2: Write the migration**

```sql
-- db-migrations/policy/V10__one_policy_per_underwriting_case.sql
-- One application, one contract.
--
-- V4 added underwriting_case_id as a plain nullable column with no FK and no uniqueness, and
-- PolicyRepository had no finder for it at all, so nothing on the platform could answer "has
-- this case already been issued?". Both issuance paths -- the decision listener and
-- POST /policies/manual-issue -- call the same issuePolicy, so a case could produce unlimited
-- policies. The listener's own error handling documents manual issue as the retry for a failed
-- automatic issuance, which makes the duplicate the EXPECTED sequence after a transient failure
-- rather than an exotic edge case.
--
-- Partial, on two counts. Pre-M6 policies have NULL here and V4's own comment records that
-- there is no source of truth to backfill from. Group schemes (V9) pass null deliberately --
-- a scheme is not underwritten as one case -- and must stay issuable.
CREATE UNIQUE INDEX ux_policy_underwriting_case
    ON policy.policy (tenant_id, underwriting_case_id)
    WHERE underwriting_case_id IS NOT NULL;
```

- [ ] **Step 3: Write the failing test**

`openCaseAndAssess()` does not exist in `PolicyApiIntegrationTest` yet. Add it as a private helper in that file, built from the file's own existing product-publishing and party-registration helpers, returning the opened case after one `MEDICAL` assessment has been submitted against it. Task 7's test can then use it too.

```java
@Test
void oneUnderwritingCaseCannotProduceTwoPolicies() {
    UnderwritingCaseView opened = openCaseAndAssess();
    underwritingApi.decide(opened.caseId(),
        new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "uw@nlolo.co.tz", false);

    PolicyApi.IssueRequest second = new PolicyApi.IssueRequest(
        opened.applicantPartyId(), productId, productVersionId,
        new BigDecimal("5000000"), "TZS", new BigDecimal("25000"), "TZS", "MONTHLY",
        null, List.of(), "Operator retrying a issuance they thought had failed");

    PolicyAlreadyIssuedForCaseException thrown = assertThrows(PolicyAlreadyIssuedForCaseException.class,
        () -> policyApi.issuePolicy(opened.caseId(), second, "staff.finance"));

    assertTrue(thrown.getMessage().contains("POL-"),
        "the message must name the policy that already exists, or the operator cannot go and look at it");
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest='PolicyApiIntegrationTest#oneUnderwritingCaseCannotProduceTwoPolicies'`
Expected: FAIL — no exception thrown, a second policy is created.

- [ ] **Step 5: Create the exception**

```java
package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

/**
 * An underwriting case that already has a policy was issued again.
 *
 * <p>Names the existing policy number deliberately. The operator reaching this is almost
 * always someone who believes the first issuance failed, and the only useful answer is where
 * to find the one that succeeded.
 */
public class PolicyAlreadyIssuedForCaseException extends RuntimeException {
    public PolicyAlreadyIssuedForCaseException(UUID underwritingCaseId, String existingPolicyNumber) {
        super("Underwriting case " + underwritingCaseId + " was already issued as policy "
            + existingPolicyNumber);
    }
}
```

- [ ] **Step 6: Add the finder and the guard**

In `PolicyRepository`:

```java
    /**
     * The policy issued from a given underwriting case, if any.
     *
     * <p>{@code ux_policy_underwriting_case} guarantees at most one. Tenant-scoped rather than
     * relying on row-level security alone, matching every other finder here.
     */
    Optional<Policy> findByTenantIdAndUnderwritingCaseId(UUID tenantId, UUID underwritingCaseId);
```

In `PolicyApiImpl.issuePolicy`, immediately after `UUID tenantId = TenantContext.get();`:

```java
        // Before anything is written. The unique index is the real guarantee, but a constraint
        // violation surfaces as a 500 with a Postgres message, and the operator who reaches
        // this needs the policy number of the one that already exists.
        if (underwritingCaseId != null) {
            policyRepository.findByTenantIdAndUnderwritingCaseId(tenantId, underwritingCaseId)
                .ifPresent(existing -> {
                    throw new PolicyAlreadyIssuedForCaseException(underwritingCaseId, existing.getPolicyNumber());
                });
        }
```

- [ ] **Step 7: Map it to 409**

In `PolicyExceptionHandler`:

```java
    @ExceptionHandler(PolicyAlreadyIssuedForCaseException.class)
    public ProblemDetail handleAlreadyIssued(PolicyAlreadyIssuedForCaseException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "POLICY_ALREADY_ISSUED_FOR_CASE");
    }
```

Add the `409` response to `/policies/manual-issue` in `openapi-policy.yaml`.

- [ ] **Step 8: Run the full suite**

Run: `cd backend && ./mvnw clean test`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add backend/db-migrations/policy/V10__one_policy_per_underwriting_case.sql \
        backend/src/main/java/tz/co/nlolo/lifeplatform/policy \
        backend/api/openapi/openapi-policy.yaml \
        backend/src/test/java/tz/co/nlolo/lifeplatform/policy
git commit -m "feat(policy): one underwriting case can issue exactly one policy"
```

---

## Task 9: Manual issue names a real case

**Files:**
- Modify: `frontend/src/features/policies/policyIssueForm.ts`, `frontend/src/features/policies/IssuePolicyPage.tsx`
- Test: `frontend/src/features/policies/policyIssueForm.test.ts`

**Interfaces:**
- Consumes: `listCases` from `@/api/underwriting`.
- Produces: `PolicyIssueFormValues.underwritingCaseId: string` (a real uuid, required).

- [ ] **Step 1: Write the failing test**

```ts
it('refuses a request with no underwriting case, rather than inventing one', () => {
  const values = { ...blankPolicyIssueForm(), underwritingCaseId: '' };
  const result = policyIssueFormSchema.safeParse(values);
  expect(result.success).toBe(false);
  expect(issuesFor(result, 'underwritingCaseId')).toContain('Choose the underwriting case this policy is issued from');
});

it('sends the case id it was given, unchanged', () => {
  const caseId = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';
  const request = toApiRequest({ ...validPolicyIssueForm(), underwritingCaseId: caseId });
  expect(request.underwritingCaseId).toBe(caseId);
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npm test -- policyIssueForm`
Expected: FAIL — the field is not on the schema, and `toApiRequest` returns a fresh random uuid.

- [ ] **Step 3: Put the field on the schema**

Replace the module doc's justification for synthesising, and add the field:

```ts
  /**
   * The underwriting case this policy is issued from. Required, and it must be real.
   *
   * `toApiRequest` used to fill this with `crypto.randomUUID()`, on the reasoning that the
   * server never validated it and no case search endpoint existed for staff to find a real
   * one. The first half is no longer true -- `issuePolicy` now refuses a case that already
   * has a policy, which is the check that stops one application becoming two contracts -- and
   * the second half was made false by the underwriting queue. A fabricated id defeated both:
   * it pointed at nothing, so contestability lookups on every manually issued policy resolved
   * to nothing while looking like a real answer.
   */
  underwritingCaseId: z
    .string()
    .trim()
    .refine((v) => UUID_PATTERN.test(v), 'Choose the underwriting case this policy is issued from'),
```

And in `toApiRequest`, replace the synthesised line with `underwritingCaseId: values.underwritingCaseId,`. Add `underwritingCaseId: ''` to `blankPolicyIssueForm()`.

- [ ] **Step 4: Add the picker to the page**

In `IssuePolicyPage.tsx`, add a required field above the policyholder field that searches decided cases via `listCases({ status: 'DECIDED' })` and sets `underwritingCaseId`.

Build it by copying the structure of `frontend/src/components/PartyPicker.tsx` — read that file first and mirror its search-debounce, result-list, keyboard and selected-state handling, including how it reports "nothing found". Do not invent a second picker idiom; the console has one and this should look like it.

Selecting a case prefills policyholder, product version and sum assured from the case and leaves them editable. That is what turns this screen from a parallel data-entry form into a review step, and it is the difference between staff tolerating the new requirement and working around it.

Show each result by proposal number and applicant name — the proposal number is the thing staff quote to each other and it is the case's own human-readable key (`ux_underwriting_case_proposal_number`). Do not show the case uuid as the primary label.

- [ ] **Step 5: Run the tests**

Run: `cd frontend && npm run typecheck && npm run lint && npm test`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/policies
git commit -m "fix(console): manual issue names the real underwriting case instead of inventing one"
```

---

## Task 10: The decision panel in the console

**Files:**
- Create: `frontend/src/features/underwriting/decideForm.ts`, `decideForm.test.ts`, `DecisionPanel.tsx`, `DecisionPanel.test.tsx`
- Modify: `frontend/src/auth/claims.ts`, `frontend/src/api/underwriting.ts`, `frontend/src/store/underwritingStore.ts`, `frontend/src/features/underwriting/UnderwritingCaseDetailPage.tsx`

**Interfaces:**
- Consumes: `POST /underwriting/cases/{caseId}/decision`.
- Produces: `decide(caseId, request)` on the api and the store; `StaffRoles.SENIOR_UNDERWRITER`.

- [ ] **Step 1: Add the role to the token helper**

In `claims.ts`, add `SENIOR_UNDERWRITER: boolean;` to `StaffRoles` and `SENIOR_UNDERWRITER: has('SENIOR_UNDERWRITER'),` to `staffRoles`.

- [ ] **Step 2: Regenerate the API types**

Run: `cd frontend && npm run generate:api`

Remember the generated request type will make every non-optional property required, including `default:` ones.

- [ ] **Step 3: Add the api call and the store action**

In `api/underwriting.ts`:

```ts
/**
 * `POST /underwriting/cases/{caseId}/decision` -- the human underwriting decision.
 *
 * Submitting an assessment records evidence and the engine's advice; this is what settles the
 * case and what puts a policy in force. A decision departing from the recommendation returns
 * 403 unless the caller holds SENIOR_UNDERWRITER.
 */
export function decide(caseId: string, request: DecideRequest): Promise<UnderwritingCaseView> {
  return post<UnderwritingCaseView>(`/underwriting/cases/${encodeURIComponent(caseId)}/decision`, request);
}
```

Add `decide` and `resetDecide` to the store, following `submitAssessment`/`resetSubmitAssessment` exactly, keyed by `caseId`. On success, reload the case so the rail reflects the new status.

- [ ] **Step 4: Write the form schema and its tests**

`decideForm.ts` mirrors `DecideRequest`: `outcome` is one of the four, `reason` is required and trimmed, `loadingPercent` is a string kept as a string (an empty string is not zero), required when `outcome === 'LOADED'` and refused otherwise. Write the four cases as tests first.

- [ ] **Step 5: Write `DecisionPanel.test.tsx` first, then the component**

The panel must:
- Show the recommendation as advice — outcome, loading and the engine's reason — clearly labelled as a recommendation and not as the decision.
- Render nothing at all when the case is already decided, showing instead the decision, who made it, and a marker when it departed from the recommendation.
- Disable the submit button when the chosen outcome differs from the recommendation and the user lacks `SENIOR_UNDERWRITER`, with a line saying a senior underwriter must make that call. Do not hide the option — the junior needs to know a senior can do it.
- Surface the 403 body when the server refuses anyway. The client gate is a courtesy; the server's is the real one, and they can disagree if the recommendation changed between render and submit.

- [ ] **Step 6: Mount it and run everything**

Run: `cd frontend && npm run typecheck && npm run lint && npm test`

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(console): an underwriter decides the case, with the engine's advice beside it"
```

---

## Task 11: End-to-end, against the real stack

**Files:**
- Modify: `frontend/e2e/staff-underwriting-queue.spec.ts`, `staff-policy-lifecycle.spec.ts`, `staff-distribution.spec.ts`, `staff-group-schemes.spec.ts`

- [ ] **Step 1: Apply the migrations and restart**

The dev backend and its migrations are decoupled. Apply `underwriting/V5` and `policy/V10` to the dev Postgres by hand, add the `SENIOR_UNDERWRITER` role and `staff.senior` to Keycloak (or recreate the realm), and restart the backend. Green Testcontainers tests do not prove the dev DB is in sync.

- [ ] **Step 2: Update every spec that assumed an assessment issues a policy**

`staff-policy-lifecycle`, `staff-distribution` and `staff-group-schemes` each drive a case to a policy. Each needs the decision step added after the assessment.

- [ ] **Step 3: Add the two new specs**

In `staff-underwriting-queue.spec.ts`:

```ts
test('an assessment recommends, and an underwriter decides', async ({ page }) => {
  const { caseId } = await openRealCase(page);
  await page.goto(`/staff/underwriting/${caseId}`);

  await expect(page.getByText(/Recommended: Accept/i)).toBeVisible();
  await expect(page.getByText('DECIDED')).toHaveCount(0);

  await page.getByLabel('Decision').selectOption('ACCEPT');
  await page.getByLabel('Reason').fill('Standard risk, agrees with the recommendation');
  await page.getByRole('button', { name: 'Record decision' }).click();

  await expect(page.getByText('DECIDED')).toBeVisible({ timeout: 15_000 });
});

test('a second policy cannot be issued from a decided case', async ({ page }) => {
  const { caseId } = await openRealCaseAndDecide(page);
  await page.goto('/staff/policies/issue');
  await selectUnderwritingCase(page, caseId);
  await fillIssueFormMinimally(page);
  await page.getByRole('button', { name: 'Issue policy' }).click();

  await expect(page.getByText(/was already issued as policy POL-/)).toBeVisible({ timeout: 15_000 });
});
```

Write `openRealCaseAndDecide`, `selectUnderwritingCase` and `fillIssueFormMinimally` as local helpers in the spec, following the file's existing `openRealCase` shape.

- [ ] **Step 4: Run the full e2e suite**

Run: `cd frontend && npm run test:e2e`

Two known traps: never run this concurrently with a Maven build, and a run spanning a machine sleep produces failures that are not code.

- [ ] **Step 5: Commit**

```bash
git add frontend/e2e
git commit -m "test(e2e): the decision step, and the refusal of a second policy from one case"
```

---

## Task 12: Update the written record

**Files:**
- Modify: `backend/docs/01-domain-map.md`, `backend/docs/05-event-catalog.md`, `backend/api/openapi/openapi-policy.yaml`

- [ ] **Step 1: Correct the event catalogue**

`underwriting.UnderwritingDecisionMade` now carries `decidedBy` and is published by `decide`, not `submitAssessment`. Update the channel description in `api/asyncapi-events.yaml` too if it names the producer.

- [ ] **Step 2: Correct the policy API description**

`openapi-policy.yaml`'s header says manual issue exists "solely for exception handling". That is still the intent, but it now also requires a real case and refuses a second policy for one. Say so in one sentence.

- [ ] **Step 3: Record what Stage 1 deliberately did not do**

Add a short section to `backend/docs/research/life-new-business-flow.md` under §6 listing what remains open after this change: the offer/acceptance/first-premium stage (policies still go in force before any money), the capture gap (policy term, premium-paying term, payment frequency and beneficiary nominations are still not recorded at proposal), the bypass types for genuine no-underwriting business, the case-to-policy back-reference, `NOT_TAKEN_UP`, the s.119 free-look dates, and re-opening a declined case.

- [ ] **Step 4: Commit**

```bash
git add backend/docs backend/api
git commit -m "docs: the decision is a human act now, and here is what Stage 1 left open"
```

---

## Out of scope for Stage 1

Named so nobody implements them by accident, and so the gaps are on the record:

- **Offer, acceptance and first premium.** A policy still goes in force on an accepted decision, before the customer has agreed to the premium or paid anything. This is the single largest remaining divergence from the researched flow.
- **The capture gap.** Nothing records the requested policy term, premium-paying term, payment frequency or beneficiary nominations at proposal, so the automatic path still issues without them. Task 7 fixes only what the case already holds and discards.
- **Bypass types on manual issue** (`MIGRATION`, `GUARANTEED_ISSUE`, `CONVERSION`, `REINSTATEMENT`) — Stage 2.
- **The case-to-policy back-reference** and a case status meaning issued — Stage 2.
- **Re-opening a declined case.** Still impossible; only `POSTPONED` can be reworked.
- **The s.119 free-look dates** (proposal signed, policy delivered) and a free-look cancellation distinct from surrender — Stage 3.
- **KYC is not required** to open a case or issue a policy anywhere in the backend; only the console's party picker filters to verified people.
