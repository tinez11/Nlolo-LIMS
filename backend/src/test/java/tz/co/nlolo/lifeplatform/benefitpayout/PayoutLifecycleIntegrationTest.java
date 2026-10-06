package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.benefitpayout.application.BenefitPayoutApiImpl;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the policy's own life does to the money it is owed.
 *
 * <p>Every test here drives a REAL lifecycle method -- {@code lapsePolicy}, {@code reinstatePolicy},
 * {@code makePaidUp}, {@code approveSurrender}, {@code decideSettlement} -- rather than publishing
 * the envelope by hand. That is the point of the class: the schedule reacts to events whose payloads
 * another module writes, and a test that writes those payloads itself would stay green through
 * exactly the rename that breaks production.
 *
 * <p>No drain runs here. {@code fallDue} is called on the one instalment a test needs, because the
 * drain works across every tenant in the database and would quietly reshape the policies belonging
 * to the other tests in this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(PayoutTestFixtures.class)
class PayoutLifecycleIntegrationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final BigDecimal SUM_ASSURED = new BigDecimal("1000000.00");

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
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private BenefitPayoutApi api;
    @Autowired private BenefitPayoutApiImpl engine;
    @Autowired private PayoutTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ClaimsApi claimsApi;

    /**
     * An endowment that pays a slice of the sum assured on each of a run of anniversaries, and the
     * whole of it at maturity. The MATURITY row is not optional decoration: the validator refuses
     * an endowment version without exactly one.
     */
    private static PayoutPlan survivalEndowment(int fromYear, int toYear, String percentEachYear,
                                                Boolean deductFromDeath, String deathPremiumPercent) {
        return PayoutPlan.authored(
            new PayoutTerms(30, null, deductFromDeath,
                deathPremiumPercent == null ? null : new BigDecimal(deathPremiumPercent)),
            List.of(new PayoutRowInput(PayoutKind.SURVIVAL, fromYear, toYear, PayoutAmountBasis.PERCENT_OF_SA,
                        new BigDecimal(percentEachYear), PayoutFrequency.ANNUAL),
                    new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA,
                        new BigDecimal("100"), null)));
    }

    /** An endowment with no living benefits before maturity, for the death-benefit rules alone. */
    private static PayoutPlan maturityOnly(String deathPremiumPercent) {
        return PayoutPlan.authored(
            new PayoutTerms(30, null, null,
                deathPremiumPercent == null ? null : new BigDecimal(deathPremiumPercent)),
            List.of(new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA,
                new BigDecimal("100"), null)));
    }

    private <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void asTenant(Runnable work) {
        asTenant(() -> { work.run(); return null; });
    }

    /** The policy's schedule as {due date -> status}, which is what every assertion here is about. */
    private Map<LocalDate, InstalmentStatus> statusByDueDate(String policyNumber) {
        return asTenant(() -> api.listForPolicy(policyNumber).stream()
            .collect(java.util.stream.Collectors.toMap(PayoutInstalmentView::dueDate,
                PayoutInstalmentView::status)));
    }

    @Test
    void aLapseWithdrawsWhatIsAheadAndReinstatementBringsBackOnlyTheFuture() {
        LocalDate commencement = LocalDate.now().minusYears(3);
        // Five yearly survival benefits, so three anniversaries are behind us and two ahead.
        String policyNumber = fixtures.issueEndowment(TENANT,
            survivalEndowment(1, 5, "10", false, null), SUM_ASSURED, 120, commencement);

        assertThat(statusByDueDate(policyNumber)).hasSize(6)
            .containsKeys(commencement.plusYears(1), commencement.plusYears(3), commencement.plusYears(5))
            .containsValue(InstalmentStatus.SCHEDULED);

        asTenant(() -> policyApi.lapsePolicy(policyNumber, "billing-sweep"));

        // Everything from the lapse day onwards is withdrawn -- including the anniversary that
        // falls exactly today, which the policy is no longer on risk for.
        Map<LocalDate, InstalmentStatus> afterLapse = statusByDueDate(policyNumber);
        assertThat(afterLapse.get(commencement.plusYears(3))).isEqualTo(InstalmentStatus.CANCELLED);
        assertThat(afterLapse.get(commencement.plusYears(4))).isEqualTo(InstalmentStatus.CANCELLED);
        assertThat(afterLapse.get(commencement.plusYears(5))).isEqualTo(InstalmentStatus.CANCELLED);
        // The two that fell due while the policy was in force are untouched by the lapse.
        assertThat(afterLapse.get(commencement.plusYears(1))).isEqualTo(InstalmentStatus.SCHEDULED);
        assertThat(afterLapse.get(commencement.plusYears(2))).isEqualTo(InstalmentStatus.SCHEDULED);

        asTenant(() -> policyApi.reinstatePolicy(policyNumber, "finance-officer"));

        // Only what is still AHEAD comes back. The anniversary dated the day of the lapse stays
        // forfeited: nobody paid for the cover that would have earned it.
        Map<LocalDate, InstalmentStatus> afterReinstatement = statusByDueDate(policyNumber);
        assertThat(afterReinstatement.get(commencement.plusYears(3))).isEqualTo(InstalmentStatus.CANCELLED);
        assertThat(afterReinstatement.get(commencement.plusYears(4))).isEqualTo(InstalmentStatus.SCHEDULED);
        assertThat(afterReinstatement.get(commencement.plusYears(5))).isEqualTo(InstalmentStatus.SCHEDULED);
    }

    @Test
    void aPaidUpConversionRestatesEveryFuturePayoutByTheSameProportion() {
        LocalDate commencement = LocalDate.now().minusYears(3);
        // 20-year cover, premiums payable over 90 months, three years of them paid: 36/90 of the
        // sum assured survives the conversion.
        String policyNumber = fixtures.issueSavingsEndowment(TENANT,
            survivalEndowment(4, 5, "10", false, null), SUM_ASSURED, 240, 90, commencement);
        asTenant(() -> policyApi.recalculateCashValue(policyNumber, commencement.plusYears(3)));

        assertThat(asTenant(() -> policyApi.makePaidUp(policyNumber, "finance-officer")).sumAssuredAmount())
            .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("400000.00"));

        // 1,000,000 x 36/90 = 400,000, so every figure is now two fifths of what was authored --
        // the survival benefits at 100,000 each and the maturity at the whole sum assured alike.
        // The original stands beside the new figure: what the contract promised is not erased.
        List<PayoutInstalmentView> schedule = asTenant(() -> api.listForPolicy(policyNumber));
        assertThat(schedule).hasSize(3).allSatisfy(i -> {
            assertThat(i.restatementReason()).isEqualTo("Made paid-up: 400000.00 of 1000000.00 sum assured");
            assertThat(i.currentAmount()).usingComparator(BigDecimal::compareTo).isEqualTo(
                i.originalAmount().multiply(new BigDecimal("0.4")).setScale(2, java.math.RoundingMode.HALF_EVEN));
        });
        assertThat(schedule).extracting(PayoutInstalmentView::currentAmount)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("40000.00"), new BigDecimal("40000.00"), new BigDecimal("400000.00"));
    }

    @Test
    void aSurrenderEndsTheWholeSchedule() {
        LocalDate commencement = LocalDate.now().minusYears(3);
        String policyNumber = fixtures.issueSavingsEndowment(TENANT,
            survivalEndowment(4, 5, "10", false, null), SUM_ASSURED, 240, 90, commencement);
        asTenant(() -> policyApi.recalculateCashValue(policyNumber, commencement.plusYears(3)));

        PolicyApi.SurrenderRequestView request = asTenant(() ->
            policyApi.requestSurrender(policyNumber, "+255700000001", "service-officer"));
        asTenant(() -> policyApi.approveSurrender(request.surrenderRequestId(), "finance-officer"));

        assertThat(asTenant(() -> policyApi.getPolicy(policyNumber)).status()).isEqualTo(PolicyStatus.SURRENDERED);
        // The contract has been bought back. Nothing under it is owed any longer, including the
        // maturity the customer would have reached in seventeen years.
        assertThat(asTenant(() -> api.listForPolicy(policyNumber)))
            .hasSize(3)
            .allSatisfy(i -> {
                assertThat(i.status()).isEqualTo(InstalmentStatus.CANCELLED);
                assertThat(i.statusReason()).isEqualTo("Policy surrendered");
            });
    }

    @Test
    void anApprovedDeathClaimEndsTheLivingBenefits() {
        LocalDate commencement = LocalDate.now().minusYears(1);
        String policyNumber = fixtures.issueEndowment(TENANT,
            survivalEndowment(1, 5, "10", false, null), SUM_ASSURED, 240, commencement);

        LocalDate dateOfDeath = LocalDate.now().minusDays(1);
        UUID claimId = asTenant(() -> {
            UUID claimant = partyApi.registerIndividual("Payout Death Claimant", LocalDate.of(1980, 3, 3),
                "+255715000001", null, "test-agent").partyId();
            UUID id = claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimant,
                ClaimType.DEATH, dateOfDeath,
                new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfDeath, "Dr. Test")),
                "payout-death-reg-01", "claims-clerk").claimId();
            claimsApi.submitAssessment(id, "Consistent with cause of death", SUM_ASSURED, "TZS", false,
                "assessor-01", null);
            claimsApi.decideSettlement(id, true, SUM_ASSURED, "TZS", null, "+255700000002",
                "payout-death-settle-01", "manager-01");
            return id;
        });
        assertThat(claimId).isNotNull();

        // Every benefit this module schedules is paid WHILE THE LIFE ASSURED LIVES. The death
        // benefit replaces them, and a survival benefit still standing would be sent to someone the
        // insurer has just been told is dead.
        assertThat(asTenant(() -> api.listForPolicy(policyNumber)))
            .hasSize(6)
            .allSatisfy(i -> {
                assertThat(i.status()).isEqualTo(InstalmentStatus.CANCELLED);
                assertThat(i.statusReason()).isEqualTo("Death claim approved");
            });
    }

    @Test
    void anEndOfTermPayoutOnAPolicyWithNoTermIsRefusedByName() {
        // due_date is NOT NULL, so a maturity row on a policy with no maturity date used to fail
        // as a constraint violation INSIDE the AFTER_COMMIT listener -- which swallowed it and
        // left the policy with no schedule at all, survival rows included, because the whole
        // expansion rolled back. The only trace was "null value in column due_date".
        UUID tenant = UUID.randomUUID();
        String policyNumber = fixtures.issue(tenant, ProductCategory.ENDOWMENT, maturityOnly(null),
            CashValuePlan.none(), SUM_ASSURED, null, null, LocalDate.now(), "MONTHLY");

        TenantContext.set(tenant);
        try {
            // No schedule, and the refusal named the policy rather than a column.
            assertThat(api.listForPolicy(policyNumber)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aMaturityClaimIsRefusedOnAPolicyWhoseMaturityIsAlreadyScheduled() {
        // A twelve-month endowment that matured today: the maturity instalment is already owed and
        // dated, and finance will review and approve it.
        LocalDate commencement = LocalDate.now().minusYears(1);
        String policyNumber = fixtures.issueEndowment(TENANT, maturityOnly(null), SUM_ASSURED, 12, commencement);
        assertThat(asTenant(() -> api.hasScheduledMaturity(policyNumber))).isTrue();

        UUID claimant = asTenant(() -> partyApi.registerIndividual("Payout Maturity Claimant",
            LocalDate.of(1979, 4, 4), "+255715000002", null, "test-agent").partyId());

        assertThatThrownBy(() -> asTenant(() -> claimsApi.registerClaim(
                new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimant, ClaimType.MATURITY,
                    LocalDate.now(), null),
                "payout-maturity-reg-01", "claims-clerk")))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("pays its maturity benefit on a schedule");
    }

    @Test
    void theDeathBenefitDropsBySurvivalBenefitsAlreadyPaidWhenTheProductSaysSo() {
        LocalDate commencement = LocalDate.now().minusYears(2);
        String policyNumber = fixtures.issueEndowment(TENANT,
            survivalEndowment(1, 2, "10", true, null), SUM_ASSURED, 240, commencement);
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("600000.00"), LocalDate.now());

        // Pay the first anniversary's 100,000 through the real transitions, so the deduction is
        // computed from a payout that genuinely went out rather than a row written to look paid.
        UUID first = asTenant(() -> api.listForPolicy(policyNumber).get(0).instalmentId());
        asTenant(() -> {
            engine.fallDue(first);
            api.review(first, "+255700000003", tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod.IN_PERSON,
                null, "reviewer-01");
            api.approve(first, "approver-02");
            engine.markPaid(first, UUID.randomUUID());
        });
        assertThat(asTenant(() -> api.getInstalment(first).status())).isEqualTo(InstalmentStatus.PAID);

        // Guide §7: on this product what has already been paid out alive comes off what is paid on
        // death. 1,000,000 of cover less the 100,000 banked = 900,000.
        assertThat(asTenant(() -> api.deathBenefitCeiling(policyNumber, SUM_ASSURED)))
            .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("900000.00"));
    }

    @Test
    void theDeathBenefitNeverFallsBelowTheProductsGuaranteedShareOfPremiums() {
        String policyNumber = fixtures.issueEndowment(TENANT, maturityOnly("110"), SUM_ASSURED, 240,
            LocalDate.now().minusYears(2));
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("1200000.00"), LocalDate.now());

        // Guide §6: the higher of the sum assured and 110% of premiums paid. 1,200,000 x 110% =
        // 1,320,000, which is more than the cover, so a family is not paid less than was put in.
        assertThat(asTenant(() -> api.deathBenefitCeiling(policyNumber, SUM_ASSURED)))
            .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("1320000.00"));

        // The claim screen's "Covered for" is that same limit, not the sum assured (product step 4,
        // plan revision R1): it used to show 1,000,000 while approval allowed up to 1,320,000.
        LocalDate dateOfDeath = LocalDate.now().minusDays(1);
        UUID claimId = asTenant(() -> {
            UUID claimant = partyApi.registerIndividual("Payout Floor Claimant", LocalDate.of(1980, 3, 3),
                "+255715000009", null, "test-agent").partyId();
            return claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimant,
                ClaimType.DEATH, dateOfDeath,
                new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfDeath, "Dr. Test")),
                "payout-floor-reg-01", "claims-clerk").claimId();
        });
        assertThat(asTenant(() -> claimsApi.claimableCover(claimId)).amount())
            .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("1320000.00"));

        // And a policy whose version says neither rule keeps exactly the ceiling it always had.
        String plain = fixtures.issueEndowment(TENANT, maturityOnly(null), SUM_ASSURED, 240,
            LocalDate.now().minusYears(2));
        assertThat(asTenant(() -> api.deathBenefitCeiling(plain, SUM_ASSURED)))
            .usingComparator(BigDecimal::compareTo).isEqualTo(SUM_ASSURED);
    }
}
