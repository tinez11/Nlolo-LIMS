package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
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
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        var snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(), sumAssured, "TZS", null, "agent1").caseId();
    }

    // ---- What the proposal states about the contract (V6) --------------------------------
    //
    // Term, premium-paying term, payment frequency and beneficiary nominations lived only on
    // POST /policies/manual-issue, which made it the only screen on this platform that could
    // produce a complete policy: one issued on the normal path carried no term, no maturity
    // date, and nobody nominated, because nobody had ever asked.

    /** A product whose version declares real term bounds, for the eligibility tests below. */
    private record BoundedProduct(UUID productId, UUID productVersionId, UUID applicantPartyId) {}

    private BoundedProduct openBoundedProduct(Integer minTermMonths, Integer maxTermMonths) {
        var applicant = partyApi.registerIndividual("Term Test Applicant", LocalDate.of(1990, 1, 1),
            "+2557123" + String.format("%05d", Math.abs(UUID.randomUUID().hashCode() % 100000)), null, "test");
        var product = productApi.createProduct("UW-TERM-" + UUID.randomUUID().toString().substring(0, 8),
            "UW Term Bounds Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, null,
            new EligibilityBounds(null, null, minTermMonths, maxTermMonths, null, null),
            ANY_FILING, "actuary");
        var snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new BoundedProduct(product.productId(), snapshot.productVersionId(), applicant.partyId());
    }

    private UnderwritingCaseView openWithProposal(BoundedProduct p, ProposalDetails proposal) {
        return underwritingApi.openCase(p.applicantPartyId(), p.productId(), p.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, proposal, "agent1");
    }

    @Test
    void aProposalRecordsTheContractTheApplicantAskedFor() {
        BoundedProduct p = openBoundedProduct(12, 240);

        UnderwritingCaseView view = openWithProposal(p, new ProposalDetails(
            null, "Dar HQ", "AGENCY", LocalDate.now(), 120, 60, "QUARTERLY", List.of()));

        assertEquals(120, view.requestedTermMonths());
        assertEquals(60, view.premiumPayingTermMonths());
        assertEquals("QUARTERLY", view.premiumFrequency());
    }

    /**
     * The check that had nowhere to run.
     *
     * <p>These bounds have existed on the product version since Build 3 and were enforced in
     * exactly one place: {@code issueGates} on the console's manual issue form. The case
     * carried no term, so on the normal path a risk was assessed, decided and issued without
     * the product's own term rules ever being consulted.
     */
    @Test
    void aTermBelowTheProductsMinimumIsRefused() {
        BoundedProduct p = openBoundedProduct(12, 240);

        UnderwritingValidationException thrown = assertThrows(UnderwritingValidationException.class, () ->
            openWithProposal(p, new ProposalDetails(null, null, null, null, 6, null, null, List.of())));
        assertTrue(thrown.getMessage().contains("below this product's minimum"), thrown.getMessage());
    }

    @Test
    void aTermAboveTheProductsMaximumIsRefused() {
        BoundedProduct p = openBoundedProduct(12, 240);

        assertThrows(UnderwritingValidationException.class, () ->
            openWithProposal(p, new ProposalDetails(null, null, null, null, 360, null, null, List.of())));
    }

    /**
     * A null term is not a violation. Whole life, an annuity and an annually renewable group
     * scheme all genuinely have none, and the bounds are optional on the product too.
     */
    @Test
    void aProposalWithNoTermIsAcceptedEvenAgainstABoundedProduct() {
        BoundedProduct p = openBoundedProduct(12, 240);

        UnderwritingCaseView view = openWithProposal(p,
            new ProposalDetails(null, null, null, null, null, null, "MONTHLY", List.of()));

        assertNull(view.requestedTermMonths());
        assertEquals(UnderwritingCaseStatus.OPEN, view.status());
    }

    @Test
    void beneficiaryNominationsAreRecordedOnTheProposalAndReadBack() {
        BoundedProduct p = openBoundedProduct(null, null);
        var child = partyApi.registerIndividual("Nominated Child", LocalDate.of(2015, 5, 5), "+255712399001", null, "test");

        UnderwritingCaseView opened = openWithProposal(p, new ProposalDetails(
            null, null, null, null, 120, null, "MONTHLY",
            List.of(new BeneficiaryNomination(NominationType.PARTY, child.partyId(), null, new BigDecimal("60"), true),
                    new BeneficiaryNomination(NominationType.FREEFORM, null, "The estate", new BigDecimal("40"), false))));

        assertEquals(2, opened.beneficiaries().size());
        // Read back through getCase, not just the openCase response: this is the read the
        // issuance listener uses, and a nomination it cannot see is a policy issued to nobody.
        UnderwritingCaseView reread = underwritingApi.getCase(opened.caseId());
        assertEquals(2, reread.beneficiaries().size());
        assertEquals(child.partyId(), reread.beneficiaries().get(0).partyId());
        assertEquals("The estate", reread.beneficiaries().get(1).freeformDesignee());
        assertFalse(reread.beneficiaries().get(1).revocable());
    }

    @Test
    void nominationsThatDoNotTotalOneHundredAreRefused() {
        BoundedProduct p = openBoundedProduct(null, null);

        UnderwritingValidationException thrown = assertThrows(UnderwritingValidationException.class, () ->
            openWithProposal(p, new ProposalDetails(null, null, null, null, null, null, null,
                List.of(new BeneficiaryNomination(NominationType.FREEFORM, null, "Half only", new BigDecimal("50"), true)))));
        assertTrue(thrown.getMessage().contains("must total 100"), thrown.getMessage());
    }

    @Test
    void aNominationCannotBeBothAPartyAndAFreeformDesignee() {
        BoundedProduct p = openBoundedProduct(null, null);
        var child = partyApi.registerIndividual("Ambiguous Nominee", LocalDate.of(2015, 5, 5), "+255712399002", null, "test");

        assertThrows(UnderwritingValidationException.class, () ->
            openWithProposal(p, new ProposalDetails(null, null, null, null, null, null, null,
                List.of(new BeneficiaryNomination(NominationType.PARTY, child.partyId(), "Also written down",
                    new BigDecimal("100"), true)))));
    }

    /** No nomination at all is routine, not incomplete -- it can still be designated later. */
    @Test
    void aProposalWithNoNominationsIsAccepted() {
        BoundedProduct p = openBoundedProduct(null, null);

        UnderwritingCaseView view = openWithProposal(p,
            new ProposalDetails(null, null, null, null, 120, null, "MONTHLY", List.of()));

        assertTrue(view.beneficiaries().isEmpty());
    }

    @Test
    void openCaseStartsAsOpenWithNoDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.getCase(caseId);
        assertEquals(UnderwritingCaseStatus.OPEN, view.status());
        assertNull(view.decisionOutcome());
    }

    /**
     * An assessment is evidence. It is not a decision.
     *
     * <p>This test used to be {@code submitAssessmentWithLowRiskScoreAcceptsTheCase} and
     * asserted exactly the defect: submitting one assessment ran the placeholder rules engine
     * and wrote its verdict straight into the decision columns, which published
     * UnderwritingDecisionMade, which issued a real policy. No person was involved anywhere in
     * that chain, and {@code SimpleRulesEngine}'s own comment says its thresholds are
     * "illustrative, not actuarially validated".
     */
    @Test
    void submitAssessmentWithLowRiskScoreRecommendsAcceptanceWithoutDeciding() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        assertEquals(UnderwritingCaseStatus.IN_REVIEW, view.status());
        assertNull(view.decisionOutcome(), "no person has decided yet");
        assertEquals(DecisionOutcome.ACCEPT, view.recommendationOutcome());
    }

    @Test
    void submitAssessmentWithHighRiskScoreRecommendsDeclining() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Serious findings", new BigDecimal("80"), "underwriter1");
        assertEquals(DecisionOutcome.DECLINED, view.recommendationOutcome());
        assertNull(view.decisionOutcome());
    }

    @Test
    void submitAssessmentWithVeryHighRiskScoreRecommendsPostponement() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Inconclusive test result", new BigDecimal("95"), "underwriter1");
        assertEquals(DecisionOutcome.POSTPONED, view.recommendationOutcome());
        assertNull(view.decisionOutcome());
    }

    /**
     * Fresh evidence supersedes the advice given on the last lot.
     *
     * <p>The engine reads every assessment on the case each time, so a second, worse finding
     * moves the recommendation. This is only safe because the recommendation is advice: the
     * old code wrote this straight into the decision columns, and the guard against
     * overwriting a real decision is what forced POSTPONED to be carved out as an exception.
     */
    @Test
    void furtherEvidenceRecomputesTheRecommendation() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, AssessmentType.FINANCIAL, "Income unverifiable", new BigDecimal("80"), "underwriter1");

        assertEquals(DecisionOutcome.DECLINED, view.recommendationOutcome());
        assertEquals(UnderwritingCaseStatus.IN_REVIEW, view.status(),
            "a case gathering evidence stays in review however much arrives");
    }

    private UUID assessedCase() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        return caseId;
    }

    @Test
    void anUnderwriterDecidesInLineWithTheRecommendation() {
        UUID caseId = assessedCase();

        UnderwritingCaseView decided = underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Agrees with the engine"),
            "decider1", false);

        assertEquals(UnderwritingCaseStatus.DECIDED, decided.status());
        assertEquals(DecisionOutcome.ACCEPT, decided.decisionOutcome());
        assertEquals("decider1", decided.decisionDecidedBy());
        assertFalse(decided.decisionOverrodeRecommendation());
    }

    @Test
    void departingFromTheRecommendationNeedsASeniorUnderwriter() {
        UUID caseId = assessedCase();

        assertThrows(SeniorUnderwriterApprovalRequiredException.class, () ->
            underwritingApi.decide(caseId,
                new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Adverse family history disclosed off-system"),
                "decider1", false));

        assertEquals(UnderwritingCaseStatus.IN_REVIEW, underwritingApi.getCase(caseId).status(),
            "a refused override must leave the case exactly as it was");
    }

    @Test
    void aSeniorUnderwriterMayDepartFromTheRecommendation() {
        UUID caseId = assessedCase();

        UnderwritingCaseView decided = underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Adverse family history disclosed off-system"),
            "senior1", true);

        assertEquals(DecisionOutcome.DECLINED, decided.decisionOutcome());
        assertTrue(decided.decisionOverrodeRecommendation());
        assertEquals("senior1", decided.decisionDecidedBy());
    }

    @Test
    void aDecidedCaseCannotBeDecidedTwice() {
        UUID caseId = assessedCase();
        underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Agreed"), "decider1", false);

        assertThrows(UnderwritingCaseAlreadyDecidedException.class, () ->
            underwritingApi.decide(caseId,
                new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Again"), "decider1", false));
    }

    @Test
    void decidingWithNoEvidenceIsRefused() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));

        assertThrows(UnderwritingValidationException.class, () ->
            underwritingApi.decide(caseId,
                new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Nothing assessed"), "decider1", false));
    }

    @Test
    void aDecisionMustCarryAReason() {
        UUID caseId = assessedCase();

        assertThrows(UnderwritingValidationException.class, () ->
            underwritingApi.decide(caseId,
                new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "   "), "decider1", false));
    }

    /** Mirrors chk_loading_only_when_loaded, so the caller gets a sentence and not a 500. */
    @Test
    void aLoadedDecisionMustCarryALoadingAndOthersMustNot() {
        UUID caseId = assessedCase();

        assertThrows(UnderwritingValidationException.class, () ->
            underwritingApi.decide(caseId,
                new UnderwritingApi.DecisionInput(DecisionOutcome.LOADED, null, "No figure given"), "senior1", true));

        assertThrows(UnderwritingValidationException.class, () ->
            underwritingApi.decide(caseId,
                new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, new BigDecimal("25"), "Loading on a clean accept"),
                "senior1", true));
    }

    @Test
    void referToSeniorUnderwriterSetsReferralStatusIndependentlyOfDecision() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.referToSeniorUnderwriter(caseId);
        UnderwritingCaseView view = underwritingApi.getCase(caseId);
        assertEquals(ReferralStatus.REFERRED_TO_SENIOR, view.referralStatus());
        assertEquals(UnderwritingCaseStatus.OPEN, view.status()); // Referral doesn't force a decision -- U1's point.
    }

    /**
     * The window runs from the DECISION, so these two decide the case rather than merely
     * assessing it. That is not a test detail: contestability is measured from the date the
     * insurer accepted the risk, and an assessment is not an acceptance.
     */
    @Test
    void checkContestabilityIsTrueImmediatelyAfterDecision() {
        UUID caseId = assessedCase();
        underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "decider1", false);
        assertTrue(underwritingApi.checkContestability(caseId, LocalDate.now()));
    }

    @Test
    void checkContestabilityIsFalseAfterTheWindowElapses() {
        UUID caseId = assessedCase();
        underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "decider1", false);
        // TZ_CONTESTABILITY_MONTHS seed value is 24 (placeholder, see refdata migration comment).
        assertFalse(underwritingApi.checkContestability(caseId, LocalDate.now().plusMonths(25)));
    }

    @Test
    void openCaseRejectsUnknownApplicant() {
        var product = productApi.createProduct("UW-BAD-" + UUID.randomUUID().toString().substring(0, 8), "Bad Applicant Test", ProductCategory.TERM_LIFE, "TZS", "actuary");
        assertThrows(tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException.class, () ->
            underwritingApi.openCase(UUID.randomUUID(), product.productId(), UUID.randomUUID(), new BigDecimal("1000000"), "TZS", null, "agent1"));
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        return product.productId();
    }

    private DecisionOutcome recommendationForApplicantBornIn(int birthYear, String phone) {
        var applicant = partyApi.registerIndividual("Age Rated Applicant " + birthYear,
            LocalDate.of(birthYear, 1, 1), phone, null, "test");
        UUID productId = publishAgeRatedProduct();
        var snapshot = productApi.getActiveSnapshot(productId, LocalDate.now());
        UUID caseId = underwritingApi.openCase(applicant.partyId(), productId, snapshot.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, "agent1").caseId();
        // Same low risk score in both cases: age is the only thing that differs.
        //
        // Reads the RECOMMENDATION, not the decision. These tests are about whether age
        // reaches the rating at all; they were written when an assessment decided the case, so
        // the engine's verdict was only ever readable through decisionOutcome(). What they
        // assert is unchanged.
        return underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings",
            new BigDecimal("10"), "underwriter1").recommendationOutcome();
    }

    @Test
    void aYoungApplicantAndAnOldOneWithIdenticalRiskScoresRateDifferently() {
        // The whole point. These two calls differ ONLY in the applicant's date of birth, and
        // before age was rated they produced the same outcome -- which is what "age was never
        // rated" meant in practice.
        assertEquals(DecisionOutcome.ACCEPT, recommendationForApplicantBornIn(2000, "+255712345001"),
            "an applicant in the 18-39 band rates at 1.0 and should be recommended for acceptance");
        assertEquals(DecisionOutcome.DECLINED, recommendationForApplicantBornIn(1950, "+255712345002"),
            "an applicant in the 60-99 band rates at 3.0, past the decline threshold");
    }

    /**
     * A product whose only meaningful lever is OCCUPATION CLASS.
     *
     * <p>The 3.0 on the hazardous class is past SimpleRulesEngine's decline threshold of 2.5,
     * for the same reason the age-rated product above uses 3.0: an outcome that changes is the
     * only evidence visible from outside that the factor reached the engine at all. A
     * multiplier that merely moves a price would have looked identical to one that was silently
     * dropped — which is exactly what was happening.
     */
    private UUID publishOccupationRatedProduct() {
        var product = productApi.createProduct("UW-OCC-" + UUID.randomUUID().toString().substring(0, 8),
            "Occupation Rated Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-99", BigDecimal.ONE, 18, 99),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "PROF_1", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "HAZ_4", new BigDecimal("3.0"))),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        return product.productId();
    }

    private DecisionOutcome recommendationForOccupationClass(String occupationClass, String phone) {
        var applicant = partyApi.registerIndividual(new IndividualRegistration(
            "Occupation Rated Applicant", LocalDate.of(1990, 1, 1), phone, null,
            null, null, null, "Test occupation", occupationClass, null, null, null), "test");
        UUID productId = publishOccupationRatedProduct();
        var snapshot = productApi.getActiveSnapshot(productId, LocalDate.now());
        UUID caseId = underwritingApi.openCase(applicant.partyId(), productId, snapshot.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, "agent1").caseId();
        // Identical low risk score every time: the occupation class is the only difference.
        return underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings",
            new BigDecimal("10"), "underwriter1").recommendationOutcome();
    }

    /**
     * AN OCCUPATION CLASS THE ACTUARY PRICED ACTUALLY CHANGES THE ANSWER.
     *
     * <p>RiskProfile's own javadoc left OCCUPATION_CLASS out of the profile because "no
     * structured data source exists yet", and warned that a milestone adding one must extend
     * the record "not silently ignore the new data by leaving it out of the profile passed in".
     * The person record added {@code occupationClass}, the registration form collected it, the
     * client screen displayed it — and nothing rated it. A published multiplier of 3.0 on a
     * hazardous occupation reached no premium and no decision at all.
     *
     * <p>Both calls below differ in exactly one field on the person record.
     */
    @Test
    void aHazardousOccupationAndASafeOneWithIdenticalRiskScoresRateDifferently() {
        assertEquals(DecisionOutcome.ACCEPT, recommendationForOccupationClass("PROF_1", "+255712346001"),
            "a professional class rates at 1.0 and should be recommended for acceptance");
        assertEquals(DecisionOutcome.DECLINED, recommendationForOccupationClass("HAZ_4", "+255712346002"),
            "a hazardous class rates at 3.0, past the decline threshold");
    }

    /**
     * An unrecorded occupation is neutral, not an error and not a guess.
     *
     * <p>Every field on the person record below the name is optional on purpose — a registrar
     * in front of a walk-in may not have the answers. An unclassified applicant is simply not
     * a rated one.
     */
    @Test
    void anApplicantWithNoRecordedOccupationClassRatesNeutrally() {
        assertEquals(DecisionOutcome.ACCEPT, recommendationForOccupationClass(null, "+255712346003"),
            "no occupation class contributes 1.0, exactly as an unmatched band always has");
    }

    @Test
    void anApplicantWhoseAgeFallsInNoBandRatesNeutrally() {
        // 40-59 is a real gap in this product's bands. Neutral rather than an error: no
        // product on this platform records a minimum or maximum entry age, so there is
        // nothing to say whether the gap is a mistake or deliberate.
        assertEquals(DecisionOutcome.ACCEPT, recommendationForApplicantBornIn(1976, "+255712345003"),
            "an uncovered age contributes 1.0, exactly as an unmatched band always has");
    }

    // ---- An individual case insures one person ----------------------------------------------
    //
    // This used to be `anApplicantWithNoRecordedDateOfBirthRatesNeutrally`, which opened an
    // individual case with a CORPORATE party as its own life assured and asserted it rated.
    // That was the defect: a company cannot be underwritten as a life, and exactly that case --
    // on a GROUP_LIFE product -- was issued in dev as a single-life policy covering nobody. A
    // group scheme is underwritten through the group path, which skips age rating by design.

    @Test
    void aCorporateCannotBeTheLifeAssuredOfAnIndividualCase() {
        var corporate = partyApi.registerCorporate("Not A Life " + UUID.randomUUID().toString().substring(0, 8),
            "REG-" + UUID.randomUUID().toString().substring(0, 8), "+255712345004", null, "test");
        UUID productId = publishAgeRatedProduct();
        var snapshot = productApi.getActiveSnapshot(productId, LocalDate.now());

        UnderwritingValidationException refused = assertThrows(UnderwritingValidationException.class, () ->
            underwritingApi.openCase(corporate.partyId(), productId, snapshot.productVersionId(),
                new BigDecimal("1000000"), "TZS", null, "agent1"));
        assertTrue(refused.getMessage().contains("must be a person"), refused.getMessage());
    }

    @Test
    void aGroupOrCreditLifeProductIsNotProposedAsASingleLife() {
        var applicant = partyApi.registerIndividual("Single Life Applicant", LocalDate.of(1990, 1, 1), "+255712345005", null, "test");
        for (ProductCategory category : List.of(ProductCategory.GROUP_LIFE, ProductCategory.CREDIT_LIFE)) {
            var product = productApi.createProduct("UW-" + category.name().charAt(0) + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Scheme Product", category, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-99", BigDecimal.ONE, 18, 99),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary");
            var snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

            UnderwritingValidationException refused = assertThrows(UnderwritingValidationException.class, () ->
                underwritingApi.openCase(applicant.partyId(), product.productId(), snapshot.productVersionId(),
                    new BigDecimal("1000000"), "TZS", null, "agent1"));
            assertTrue(refused.getMessage().contains("not proposed as a single life"), refused.getMessage());
        }
    }

    // ---- Separation of duties ----------------------------------------------------------------

    @Test
    void theUnderwriterWhoAssessedACaseCannotDecideIt() {
        UUID caseId = assessedCase(); // assessed by underwriter1

        UnderwritingSeparationOfDutiesException refused = assertThrows(UnderwritingSeparationOfDutiesException.class, () ->
            underwritingApi.decide(caseId, new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Mine"),
                "underwriter1", false));
        assertTrue(refused.getMessage().contains("assessed"), refused.getMessage());
        // A senior is bound by it too: seniority is about overriding the engine, not about
        // marking one's own work.
        assertThrows(UnderwritingSeparationOfDutiesException.class, () ->
            underwritingApi.decide(caseId, new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Mine"),
                "underwriter1", true));
        assertEquals(UnderwritingCaseStatus.IN_REVIEW, underwritingApi.getCase(caseId).status());
    }

    @Test
    void theUserWhoOpenedACaseCannotDecideIt() {
        UUID caseId = openTestCase(new BigDecimal("1000000")); // opened by agent1
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");

        UnderwritingSeparationOfDutiesException refused = assertThrows(UnderwritingSeparationOfDutiesException.class, () ->
            underwritingApi.decide(caseId, new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Mine"),
                "agent1", false));
        assertTrue(refused.getMessage().contains("opened"), refused.getMessage());
    }

    @Test
    void submitAssessmentOnAlreadyDecidedCaseIsRejectedAndOriginalDecisionIsPreserved() {
        // Regression test for the final-review finding 3 defect: submitAssessment used to
        // unconditionally recompute and overwrite decision_outcome/decision_decline_reason
        // /decision_decided_at on a case that was already DECIDED, with no guard and no
        // history. It must now reject the second submission and leave the original
        // decision untouched.
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        UnderwritingCaseView firstDecision = underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "decider1", false);
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

        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL,
            "Inconclusive -- awaiting specialist report", new BigDecimal("95"), "underwriter1");
        UnderwritingCaseView postponed = underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.POSTPONED, null, "Awaiting a specialist report"),
            "decider1", false);
        assertEquals(DecisionOutcome.POSTPONED, postponed.decisionOutcome());
        assertEquals(UnderwritingCaseStatus.DECIDED, postponed.status());

        // The specialist report arrives and is benign. This is the call that used to throw --
        // and it must still be accepted now that POSTPONED is a real recorded decision rather
        // than something the engine produced on its own.
        UnderwritingCaseView resolved = underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL,
            "Specialist report clear", new BigDecimal("10"), "underwriter2");

        assertEquals(DecisionOutcome.ACCEPT, resolved.recommendationOutcome(),
            "the newer medical assessment supersedes the postponing one");

        UnderwritingCaseView accepted = underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Specialist report resolves it"),
            "decider2", false);
        assertEquals(DecisionOutcome.ACCEPT, accepted.decisionOutcome());
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

        assertEquals(DecisionOutcome.ACCEPT, underwritingApi.getCase(caseId).recommendationOutcome(),
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

        assertEquals(DecisionOutcome.POSTPONED, underwritingApi.getCase(caseId).recommendationOutcome(),
            "the occupational finding still stands -- a medical assessment does not answer it");
    }

    @Test
    void aDeclinedCaseStillLocks() {
        // The unlock is POSTPONED-only. A real decision still needs an explicit re-open step,
        // which is not modeled.
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Serious findings", new BigDecimal("80"), "underwriter1");
        // In line with the recommendation, so no senior is needed to record it.
        underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.DECLINED, null, "Risk outside appetite"),
            "decider1", false);
        assertEquals(DecisionOutcome.DECLINED, underwritingApi.getCase(caseId).decisionOutcome());

        assertThrows(UnderwritingCaseAlreadyDecidedException.class, () ->
            underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Actually fine", new BigDecimal("5"), "underwriter2"));
    }

    // ---- Medical disclosures ------------------------------------------------------------
    //
    // The medical_disclosure table has existed since M4 with ZERO call sites, while claims
    // computes and displays Claim.requiresContestabilityReview -- a review with nothing to
    // review, because nothing on this platform held any evidence of what had been disclosed.

    private List<DisclosureAnswer> twoAnswers() {
        return List.of(
            new DisclosureAnswer("Q1", "Have you ever been treated for heart disease?", "No", null),
            new DisclosureAnswer("Q2", "Do you smoke?", "Yes, 10 a day since 2015", "Volunteered without prompting"));
    }

    @Test
    void disclosuresRoundTripWithTheirQuestionsAndWhoRecordedThem() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));

        MedicalDisclosureView recorded = underwritingApi.recordDisclosures(caseId, twoAnswers(), "agent.senior");
        assertEquals(caseId, recorded.caseId());
        assertEquals("agent.senior", recorded.recordedBy());

        List<MedicalDisclosureView> found = underwritingApi.listDisclosures(caseId);
        assertEquals(1, found.size());
        List<DisclosureAnswer> answers = found.get(0).answers();
        assertEquals(2, answers.size());
        // The WORDING is what a contest turns on, so it must survive the round trip -- not just
        // the code, which a later edit to the form could redefine.
        assertEquals("Have you ever been treated for heart disease?", answers.get(0).question());
        assertEquals("Yes, 10 a day since 2015", answers.get(1).answer());
        assertEquals("Volunteered without prompting", answers.get(1).notes());
    }

    @Test
    void aSecondDisclosureSetIsAddedRatherThanReplacingTheFirst() {
        // Both stay on the case. Later evidence supersedes earlier evidence in the reader's
        // judgement, not by deleting what was said before -- which is the only shape a
        // non-disclosure argument can be made from.
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.recordDisclosures(caseId, twoAnswers(), "agent.senior");
        underwritingApi.recordDisclosures(caseId,
            List.of(new DisclosureAnswer("Q1", "Have you ever been treated for heart disease?",
                "Yes -- angioplasty 2021, omitted in error", "Corrected after specialist report")),
            "underwriter1");

        List<MedicalDisclosureView> found = underwritingApi.listDisclosures(caseId);
        assertEquals(2, found.size(), "the original declaration must still be on the record");
        assertEquals("agent.senior", found.get(0).recordedBy(), "oldest first");
        assertEquals("underwriter1", found.get(1).recordedBy());
    }

    @Test
    void disclosuresCanStillBeRecordedAfterTheCaseIsDecided() {
        // Deliberate. A non-disclosure usually surfaces when a claim is made, long after the
        // case closed; refusing late entries would push that evidence off the platform, which
        // is exactly where it is today.
        UUID caseId = assessedCase();
        underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(DecisionOutcome.ACCEPT, null, "Standard risk"), "decider1", false);
        assertEquals(DecisionOutcome.ACCEPT, underwritingApi.getCase(caseId).decisionOutcome());

        underwritingApi.recordDisclosures(caseId, twoAnswers(), "claims.assessor");
        assertEquals(1, underwritingApi.listDisclosures(caseId).size());
    }

    @Test
    void recordingDisclosuresDoesNotChangeTheDecision() {
        // The evidence is recorded; it is NOT rated on. Mapping a declared condition to a risk
        // score is actuarial policy this codebase does not have -- SimpleRulesEngine is an
        // explicit placeholder -- and RiskProfile requires new rating data to extend the profile
        // and the engine together rather than be invented.
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Normal findings", new BigDecimal("10"), "underwriter1");
        var before = underwritingApi.getCase(caseId);

        underwritingApi.recordDisclosures(caseId,
            List.of(new DisclosureAnswer("Q1", "Any serious illness?", "Yes -- extensive history", null)),
            "agent.senior");

        var after = underwritingApi.getCase(caseId);
        assertEquals(before.decisionOutcome(), after.decisionOutcome());
        assertEquals(before.decisionDecidedAt(), after.decisionDecidedAt());
    }

    @Test
    void anEmptyDisclosureSetIsRefused() {
        // A row that records that nothing was asked is not evidence, and would read as
        // "we asked and they declared nothing" -- the most misleading thing this table could say.
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        assertThrows(UnderwritingValidationException.class, () ->
            underwritingApi.recordDisclosures(caseId, List.of(), "agent.senior"));
    }

    @Test
    void disclosuresOnAnUnknownCaseAre404NotAnEmptyList() {
        assertThrows(UnderwritingCaseNotFoundException.class, () ->
            underwritingApi.listDisclosures(UUID.randomUUID()));
    }

    @Test
    void casesAreTenantIsolated() {
        UUID caseId = openTestCase(new BigDecimal("1000000"));
        TenantContext.clear();
        TenantContext.set(UUID.randomUUID());
        assertThrows(UnderwritingCaseNotFoundException.class, () -> underwritingApi.getCase(caseId));
    }
}
