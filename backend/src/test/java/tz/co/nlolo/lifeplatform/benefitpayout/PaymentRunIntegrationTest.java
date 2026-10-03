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
import tz.co.nlolo.lifeplatform.benefitpayout.api.*;
import tz.co.nlolo.lifeplatform.benefitpayout.application.BenefitPayoutApiImpl;
import tz.co.nlolo.lifeplatform.benefitpayout.application.PayoutDueDrain;
import tz.co.nlolo.lifeplatform.benefitpayout.application.PaymentRunDrain;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An income plan, which is the one payout shape that does not go through two people every time.
 *
 * <p>Twelve instalments a year for twenty years is a queue nobody clears, so after the stream's
 * first instalment earns its reviewer and its separate approver, the rest are batched into a daily
 * run that one person releases. The control that replaces the second signature is the
 * proof-of-life interval, and these tests are mostly about proving that control actually bites.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(PayoutTestFixtures.class)
class PaymentRunIntegrationTest {

    /**
     * A FRESH tenant per test, which is not housekeeping -- it is what the subject under test
     * requires. A payment run is one batch per tenant per day, so two tests sharing a tenant share
     * today's run and each sees the other's instalments in its own count and total.
     */
    private UUID TENANT;

    @org.junit.jupiter.api.BeforeEach
    void freshTenant() {
        TENANT = UUID.randomUUID();
    }

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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private BenefitPayoutApiImpl api;
    @Autowired private PayoutDueDrain dueDrain;
    @Autowired private PaymentRunDrain runDrain;
    @Autowired private PayoutTestFixtures fixtures;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 12,000 a year paid monthly over two policy years, and proof of life every 12 months. */
    private static final PayoutPlan INCOME = PayoutPlan.authored(
        new PayoutTerms(15, 12, null, null),
        List.of(new PayoutRowInput(PayoutKind.INCOME, 1, 2, PayoutAmountBasis.FIXED,
                    new BigDecimal("12000"), PayoutFrequency.MONTHLY),
                new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA,
                    new BigDecimal("100"), null)));

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

    /** Commenced two months and a day ago, premiums current: the first two monthly incomes are due. */
    private String incomePolicyWithTwoDue() {
        String policyNumber = fixtures.issueEndowment(TENANT, INCOME, new BigDecimal("1000000.00"), 120,
            LocalDate.now().minusMonths(2).minusDays(1));
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("200000.00"), LocalDate.now());
        dueDrain.drain();
        return policyNumber;
    }

    private List<PayoutInstalmentView> incomeWithStatus(String policyNumber, InstalmentStatus status) {
        return asTenant(() -> api.listForPolicy(policyNumber).stream()
            .filter(v -> v.kind() == PayoutKind.INCOME && v.status() == status).toList());
    }

    private List<InstalmentStatus> statusesOfStream(String policyNumber, UUID streamId) {
        return asTenant(() -> api.listForPolicy(policyNumber).stream()
            .filter(v -> streamId.equals(v.streamId())).map(PayoutInstalmentView::status).toList());
    }

    @Test
    void theFirstInstalmentActivatesTheStreamAndTheRestGoThroughARun() {
        String policyNumber = incomePolicyWithTwoDue();
        List<PayoutInstalmentView> due = incomeWithStatus(policyNumber, InstalmentStatus.DUE);
        assertThat(due).hasSize(2);

        // The stream's first instalment goes through the ordinary two-person route, which is what
        // activates the stream and confirms the destination for everything that follows.
        asTenant(() -> {
            api.review(due.get(0).instalmentId(), "+255700000009", ProofOfLifeMethod.IN_PERSON, null, "rev-1");
            api.approve(due.get(0).instalmentId(), "apr-2");
        });

        runDrain.drain();

        PaymentRunView run = asTenant(() -> api.listRuns().get(0));
        // One instalment: the other is already APPROVED, and a run only carries what is still DUE.
        assertThat(run.instalmentCount()).isEqualTo(1);
        assertThat(run.status()).isEqualTo("PREPARED");
        // 12,000 a year over twelve months, so 1,000 each -- and the TOTAL is the server's, never
        // the console's addition of a list it was shown.
        assertThat(run.total()).isEqualByComparingTo("1000.00");
        assertThat(run.runDate()).isEqualTo(LocalDate.now());

        PaymentRunView approved = asTenant(() -> api.approveRun(run.paymentRunId(), "fin-3"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedBy()).isEqualTo("fin-3");

        assertThat(asTenant(() -> api.runInstalments(run.paymentRunId())))
            .hasSize(1)
            .allSatisfy(v -> {
                assertThat(v.status()).isEqualTo(InstalmentStatus.APPROVED);
                // The destination was NOT re-entered by the batch approver: it is the one a
                // reviewer confirmed on this stream.
                assertThat(v.payeeRef()).isEqualTo("+255700000009");
                assertThat(v.approvedBy()).isEqualTo("fin-3");
                assertThat(v.paymentRunId()).isEqualTo(run.paymentRunId());
            });

        // A run is released once. A second release would request every payment in it again.
        assertThatThrownBy(() -> asTenant(() -> api.approveRun(run.paymentRunId(), "fin-4")))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageContaining("already APPROVED");
    }

    @Test
    void overdueProofOfLifeSuspendsTheStreamAndNewProofReleasesItsInstalments() {
        String policyNumber = incomePolicyWithTwoDue();
        PayoutInstalmentView first = incomeWithStatus(policyNumber, InstalmentStatus.DUE).get(0);
        UUID streamId = first.streamId();
        assertThat(streamId).isNotNull();

        asTenant(() -> {
            api.review(first.instalmentId(), "+255700000009", ProofOfLifeMethod.IN_PERSON, null, "rev-1");
            api.approve(first.instalmentId(), "apr-2");
            // What the drain does once the proof-of-life date passes.
            api.suspendStream(streamId);
        });

        // Everything still DUE on the stream is held. What was already approved is not touched --
        // that money is on its way and cannot be unsent.
        assertThat(statusesOfStream(policyNumber, streamId))
            .contains(InstalmentStatus.ON_HOLD, InstalmentStatus.APPROVED);

        // And a suspended stream contributes nothing to a run, which is the whole reason batch
        // approval is safe: the batch cannot keep paying a life nobody has confirmed.
        runDrain.drain();
        assertThat(asTenant(() -> api.listRuns())).isEmpty();

        asTenant(() -> api.recordProofOfLife(streamId, ProofOfLifeMethod.LIFE_CERTIFICATE, null, "rev-1"));
        assertThat(statusesOfStream(policyNumber, streamId)).doesNotContain(InstalmentStatus.ON_HOLD);

        runDrain.drain();
        assertThat(asTenant(() -> api.listRuns())).hasSize(1)
            .allSatisfy(r -> assertThat(r.instalmentCount()).isEqualTo(1));
    }

    @Test
    void anInstalmentHeldForARREARSStaysHeldWhenProofOfLifeIsRecorded() {
        String policyNumber = fixtures.issueEndowment(TENANT, INCOME, new BigDecimal("1000000.00"), 120,
            LocalDate.now().minusMonths(2).minusDays(1));
        /*
          The fixture issues TODAY, so billing's first premium is not due for a month and nothing
          can be in arrears. Backdating the tally's issue date makes two months of monthly premiums
          fall due with none paid -- the same device PayoutDrainIntegrationTest uses, and for the
          same reason. The test connects as the table owner, so RLS is bypassed.
        */
        jdbcTemplate.update("UPDATE benefitpayout.premium_tally "
            + "SET issue_date = current_date - 1 - interval '2 months' WHERE policy_number = ?", policyNumber);
        dueDrain.drain();

        // Both monthly incomes fell due with nothing paid, so both are held for arrears.
        List<PayoutInstalmentView> held = incomeWithStatus(policyNumber, InstalmentStatus.ON_HOLD);
        assertThat(held).hasSize(2).allSatisfy(v ->
            assertThat(v.statusReason()).isEqualTo("Premiums are not paid up to the due date"));
        UUID streamId = held.get(0).streamId();

        // A payment that cures the FIRST month only. The second income is still in arrears.
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("100000.00"),
            LocalDate.now().minusMonths(1).minusDays(1));
        assertThat(incomeWithStatus(policyNumber, InstalmentStatus.DUE)).hasSize(1);

        // Two people on the first instalment, which is what activates the stream.
        PayoutInstalmentView first = incomeWithStatus(policyNumber, InstalmentStatus.DUE).get(0);
        asTenant(() -> {
            api.review(first.instalmentId(), "+255700000009", ProofOfLifeMethod.IN_PERSON, null, "rev-1");
            api.approve(first.instalmentId(), "apr-2");
        });

        // Proving the customer is alive says nothing about what they have PAID, so the arrears hold
        // survives fresh proof of life. Releasing here would pay a benefit on a policy in arrears.
        asTenant(() -> api.recordProofOfLife(streamId, ProofOfLifeMethod.PHONE_OR_VIDEO, null, "rev-1"));
        assertThat(incomeWithStatus(policyNumber, InstalmentStatus.ON_HOLD))
            .hasSize(1)
            .allSatisfy(v -> assertThat(v.statusReason()).isEqualTo("Premiums are not paid up to the due date"));

        // And it stays out of the batch, so nothing pays it without someone curing the arrears.
        runDrain.drain();
        assertThat(asTenant(() -> api.listRuns())).isEmpty();
    }

    @Test
    void preparingARunTwiceInADayAddsToTheSameRun() {
        String policyNumber = incomePolicyWithTwoDue();
        PayoutInstalmentView first = incomeWithStatus(policyNumber, InstalmentStatus.DUE).get(0);
        asTenant(() -> {
            api.review(first.instalmentId(), "+255700000009", ProofOfLifeMethod.IN_PERSON, null, "rev-1");
            api.approve(first.instalmentId(), "apr-2");
        });

        runDrain.drain();
        runDrain.drain();

        // One run for the date, not two, and the instalment is in it exactly once. An hourly drain
        // that opened a run per pass would present the same money for approval over and over.
        List<PaymentRunView> runs = asTenant(() -> api.listRuns());
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).instalmentCount()).isEqualTo(1);
        assertThat(runs.get(0).total()).isEqualByComparingTo("1000.00");
    }
}
