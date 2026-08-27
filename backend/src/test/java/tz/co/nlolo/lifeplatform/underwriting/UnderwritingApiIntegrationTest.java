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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
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

    @Test
    void ageBandRatingIsNeutralEvenWhenProductDefinesARealNonNeutralThirtiesBand() {
        // Regression test for the final-review I5/finding-2 defect: resolveAgeBand used to
        // hardcode the literal "30-39" -- a real band name products actually define -- so
        // any product whose AGE rating table happened to declare "30-39" with a non-1.0
        // multiplier had that multiplier silently applied to every applicant regardless of
        // actual age. resolveAgeBand now returns "UNKNOWN", which cannot collide with any
        // real band, so the AGE contribution must be neutral (1.0) here even though this
        // product defines "30-39" with a strongly non-neutral multiplier (3.0).
        var applicant = partyApi.registerIndividual("Age Band Test Applicant", LocalDate.of(1990, 1, 1), "+255712345000", null, "test");
        var product = productApi.createProduct("UW-AGE-" + UUID.randomUUID().toString().substring(0, 8), "Age Band Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            // AGE 30-39 -> 3.0 is a real, strongly non-neutral multiplier. If it were
            // (incorrectly) applied, combinedMultiplier (3.0 x 1.0 = 3.0) would exceed
            // SimpleRulesEngine's DECLINE_MULTIPLIER_THRESHOLD (2.5) and this low-risk-score
            // case would be DECLINED instead of ACCEPTed.
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("3.0")),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        var snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        UUID caseId = underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(), new BigDecimal("1000000"), "TZS", "agent1").caseId();

        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");

        assertEquals(DecisionOutcome.ACCEPT, view.decisionOutcome(),
            "AGE contribution must be neutral -- the product's real '30-39' band multiplier must not be applied");
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

    @Test
    void casesAreTenantIsolated() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        TenantContext.clear();
        TenantContext.set(UUID.randomUUID());
        assertThrows(UnderwritingCaseNotFoundException.class, () -> underwritingApi.getCase(caseId));
    }
}
