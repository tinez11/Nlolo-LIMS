package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = Application.class)
class UnderwritingApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql");
    }

    @BeforeEach
    void setTenant() { TenantContext.set(UUID.randomUUID()); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Autowired
    private UnderwritingApi underwritingApi;
    @Autowired
    private PartyApi partyApi;
    @Autowired
    private ProductApi productApi;

    private UUID openTestCase(BigDecimal sumAssured) {
        var applicant = partyApi.registerIndividual("Test Applicant", LocalDate.of(1990, 1, 1), "+255712345678", null, "test");
        // NOTE (deviation from task-5-brief.md's verbatim test text): the brief's original
        // "UW-TEST-" + UUID.randomUUID() is 44 chars, exceeding product.product_definition's
        // product_code VARCHAR(30) (Task 1's frozen migration). Postgres's resulting string-
        // truncation error is mis-translated by ProductApiImpl.createProduct()'s catch
        // (DataIntegrityViolationException -> DuplicateProductCodeException) into a
        // confusing "already exists" error, not a truncation error. Each test already uses
        // its own fresh random tenant (isolating product codes), so an 8-char random suffix
        // is enough for uniqueness here while fitting the column.
        var product = productApi.createProduct("UW-TEST-" + UUID.randomUUID().toString().substring(0, 8), "UW Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        var snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(), sumAssured, "TZS", "agent1").caseId();
    }

    @Test
    void openCaseStartsAsOpenWithNoDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.getCase(caseId);
        assertEquals(UnderwritingCaseStatus.OPEN, view.status());
        assertNull(view.decisionOutcome());
    }

    @Test
    void submitAssessmentWithLowRiskScoreAcceptsTheCase() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        assertEquals(UnderwritingCaseStatus.DECIDED, view.status());
        assertEquals(DecisionOutcome.ACCEPT, view.decisionOutcome());
    }

    @Test
    void submitAssessmentWithHighRiskScoreDeclinesTheCase() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Serious findings", new BigDecimal("80"), "underwriter1");
        assertEquals(DecisionOutcome.DECLINED, view.decisionOutcome());
    }

    @Test
    void submitAssessmentWithVeryHighRiskScorePostponesTheCase() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Inconclusive test result", new BigDecimal("95"), "underwriter1");
        assertEquals(DecisionOutcome.POSTPONED, view.decisionOutcome());
    }

    @Test
    void referToSeniorUnderwriterSetsReferralStatusIndependentlyOfDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.referToSeniorUnderwriter(caseId);
        UnderwritingCaseView view = underwritingApi.getCase(caseId);
        assertEquals(ReferralStatus.REFERRED_TO_SENIOR, view.referralStatus());
        assertEquals(UnderwritingCaseStatus.OPEN, view.status()); // Referral doesn't force a decision -- U1's point.
    }

    @Test
    void checkContestabilityIsTrueImmediatelyAfterDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal", new BigDecimal("10"), "underwriter1");
        assertTrue(underwritingApi.checkContestability(caseId, LocalDate.now()));
    }

    @Test
    void checkContestabilityIsFalseAfterTheWindowElapses() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal", new BigDecimal("10"), "underwriter1");
        // TZ_CONTESTABILITY_MONTHS seed value is 24 (placeholder, see refdata migration comment).
        assertFalse(underwritingApi.checkContestability(caseId, LocalDate.now().plusMonths(25)));
    }

    @Test
    void openCaseRejectsUnknownApplicant() {
        var product = productApi.createProduct("UW-BAD-" + UUID.randomUUID().toString().substring(0, 8), "Bad Applicant Test", ProductCategory.TERM_LIFE, "TZS", "actuary");
        assertThrows(tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException.class, () ->
            underwritingApi.openCase(UUID.randomUUID(), product.productId(), UUID.randomUUID(), new BigDecimal("1000000"), "TZS", "agent1"));
    }

    // ---- Age is rated -------------------------------------------------------------------
    //
    // This block replaces `ageBandRatingIsNeutralEvenWhenProductDefinesARealNonNeutralThirtiesBand`,
    // which asserted the OPPOSITE: that a product's real AGE multiplier must NOT be applied.
    // That was a correct test of the behaviour at the time and the behaviour was the defect.
    // resolveAgeBand returned the sentinel "UNKNOWN" because PartyView carried no date of
    // birth, so every applicant resolved to a neutral 1.0 and age -- the factor that dominates
    // mortality -- was never rated. PartyDetailView exposes the date of birth, rating_table
    // carries real bounds as of V5, and the sentinel is gone.

    /** A product whose only meaningful lever is age: young applicants standard, old applicants heavily rated. */
    private UUID publishAgeRatedProduct() {
        var product = productApi.createProduct("UW-AGE-" + UUID.randomUUID().toString().substring(0, 8),
            "Age Rated Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            // 3.0 is past SimpleRulesEngine's DECLINE_MULTIPLIER_THRESHOLD of 2.5, so the two
            // bands below produce visibly different outcomes for the SAME low risk score --
            // which is the only way to show that age itself moved the decision.
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-39", BigDecimal.ONE, 18, 39),
                    new ProductApi.RatingFactorInput(FactorType.AGE, "60-99", new BigDecimal("3.0"), 60, 99),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        return product.productId();
    }

    private DecisionOutcome decideForApplicantBornIn(int birthYear, String phone) {
        var applicant = partyApi.registerIndividual("Age Rated Applicant " + birthYear,
            LocalDate.of(birthYear, 1, 1), phone, null, "test");
        UUID productId = publishAgeRatedProduct();
        var snapshot = productApi.getActiveSnapshot(productId, LocalDate.now());
        UUID caseId = underwritingApi.openCase(applicant.partyId(), productId, snapshot.productVersionId(),
            new BigDecimal("1000000"), "TZS", "agent1").caseId();
        // Same low risk score in both cases: age is the only thing that differs.
        return underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings",
            new BigDecimal("10"), "underwriter1").decisionOutcome();
    }

    @Test
    void aYoungApplicantAndAnOldOneWithIdenticalRiskScoresNowDecideDifferently() {
        // The whole point. These two calls differ ONLY in the applicant's date of birth, and
        // before this change they produced the same outcome -- which is what "age was never
        // rated" meant in practice.
        assertEquals(DecisionOutcome.ACCEPT, decideForApplicantBornIn(2000, "+255712345001"),
            "an applicant in the 18-39 band rates at 1.0 and should be accepted");
        assertEquals(DecisionOutcome.DECLINED, decideForApplicantBornIn(1950, "+255712345002"),
            "an applicant in the 60-99 band rates at 3.0, past the decline threshold");
    }

    @Test
    void anApplicantWhoseAgeFallsInNoBandRatesNeutrally() {
        // 40-59 is a real gap in this product's bands. Neutral rather than an error: no
        // product on this platform records a minimum or maximum entry age, so there is
        // nothing to say whether the gap is a mistake or deliberate.
        assertEquals(DecisionOutcome.ACCEPT, decideForApplicantBornIn(1976, "+255712345003"),
            "an uncovered age contributes 1.0, exactly as an unmatched band always has");
    }

    @Test
    void anApplicantWithNoRecordedDateOfBirthRatesNeutrally() {
        // A CORPORATE or GROUP applicant genuinely has no date of birth, and a group scheme
        // must still be underwritable. Age simply does not contribute.
        var applicant = partyApi.registerCorporate("Age Rated Corporate " + UUID.randomUUID().toString().substring(0, 8),
            "REG-" + UUID.randomUUID().toString().substring(0, 8), "+255712345004", null, "test");
        UUID productId = publishAgeRatedProduct();
        var snapshot = productApi.getActiveSnapshot(productId, LocalDate.now());
        UUID caseId = underwritingApi.openCase(applicant.partyId(), productId, snapshot.productVersionId(),
            new BigDecimal("1000000"), "TZS", "agent1").caseId();

        assertEquals(DecisionOutcome.ACCEPT, underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL,
            "Normal findings", new BigDecimal("10"), "underwriter1").decisionOutcome());
    }

    @Test
    void submitAssessmentOnAlreadyDecidedCaseIsRejectedAndOriginalDecisionIsPreserved() {
        // Regression test for the final-review finding 3 defect: submitAssessment used to
        // unconditionally recompute and overwrite decision_outcome/decision_decline_reason
        // /decision_decided_at on a case that was already DECIDED, with no guard and no
        // history. It must now reject the second submission and leave the original
        // decision untouched.
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView firstDecision = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        assertEquals(UnderwritingCaseStatus.DECIDED, firstDecision.status());
        assertEquals(DecisionOutcome.ACCEPT, firstDecision.decisionOutcome());
        // Re-read through getCase (a DB round-trip) rather than comparing against
        // firstDecision's in-memory Instant.now() directly -- Postgres' timestamp column
        // truncates sub-microsecond precision, so the in-memory and DB-round-tripped
        // Instants for the same write are not bit-for-bit equal despite representing the
        // same decision.
        UnderwritingCaseView beforeRejectedResubmit = underwritingApi.getCase(caseId);

        assertThrows(UnderwritingCaseAlreadyDecidedException.class, () ->
            underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "New serious findings", new BigDecimal("95"), "underwriter2"));

        UnderwritingCaseView afterRejectedResubmit = underwritingApi.getCase(caseId);
        assertEquals(UnderwritingCaseStatus.DECIDED, afterRejectedResubmit.status());
        assertEquals(DecisionOutcome.ACCEPT, afterRejectedResubmit.decisionOutcome(), "original decision must be preserved, not overwritten");
        assertEquals(beforeRejectedResubmit.decisionDecidedAt(), afterRejectedResubmit.decisionDecidedAt(), "decidedAt must not change on a rejected resubmission");
    }

    // ---- POSTPONED is not a decision --------------------------------------------------

    /**
     * POSTPONED used to lock a case exactly as ACCEPT and DECLINE do, which made it the one
     * outcome that could never be resolved: the engine returns it for a risk score of 90 or
     * more, asking for further medical evidence, and then refused to accept any. A postponed
     * case was work in progress wearing a terminal status.
     */
    @Test
    void aPostponedCaseAcceptsFurtherEvidenceAndCanBeResolved() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));

        UnderwritingCaseView postponed = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL,
            "Inconclusive -- awaiting specialist report", new BigDecimal("95"), "underwriter1");
        assertEquals(DecisionOutcome.POSTPONED, postponed.decisionOutcome());

        // The specialist report arrives and is benign. This is the call that used to throw.
        UnderwritingCaseView resolved = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL,
            "Specialist report clear", new BigDecimal("10"), "underwriter2");

        assertEquals(DecisionOutcome.ACCEPT, resolved.decisionOutcome(),
            "the newer medical assessment supersedes the postponing one");
    }

    /**
     * The half that makes the unlock mean anything. The engine takes the MAXIMUM risk score,
     * and it used to take it across every assessment ever recorded -- so a case postponed at
     * 95 would re-postpone forever no matter how benign the new evidence was. Scores are now
     * the latest per assessment type.
     */
    @Test
    void aSupersededScoreNoLongerDragsAPostponedCaseBack() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Inconclusive", new BigDecimal("95"), "underwriter1");
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Clear", new BigDecimal("10"), "underwriter2");

        assertEquals(DecisionOutcome.ACCEPT, underwritingApi.getCase(caseId).decisionOutcome(),
            "max-across-all-assessments would have kept this at 95 and postponed it again");
    }

    /**
     * Latest per TYPE, not latest overall. A later medical assessment must not wipe out an
     * unrelated occupational red flag -- those answer different questions, and silently
     * dropping one would discard evidence.
     */
    @Test
    void aNewAssessmentOfOneTypeDoesNotDiscardAnotherTypesFinding() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.OCCUPATIONAL, "Hazardous occupation", new BigDecimal("95"), "underwriter1");
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Clear", new BigDecimal("10"), "underwriter2");

        assertEquals(DecisionOutcome.POSTPONED, underwritingApi.getCase(caseId).decisionOutcome(),
            "the occupational finding still stands -- a medical assessment does not answer it");
    }

    @Test
    void aDeclinedCaseStillLocks() {
        // The unlock is POSTPONED-only. A real decision still needs an explicit re-open step,
        // which is not modeled.
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Serious findings", new BigDecimal("80"), "underwriter1");
        assertEquals(DecisionOutcome.DECLINED, underwritingApi.getCase(caseId).decisionOutcome());

        assertThrows(UnderwritingCaseAlreadyDecidedException.class, () ->
            underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Actually fine", new BigDecimal("5"), "underwriter2"));
    }

    @Test
    void casesAreTenantIsolated() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        TenantContext.clear();
        TenantContext.set(UUID.randomUUID());
        assertThrows(UnderwritingCaseNotFoundException.class, () -> underwritingApi.getCase(caseId));
    }
}
