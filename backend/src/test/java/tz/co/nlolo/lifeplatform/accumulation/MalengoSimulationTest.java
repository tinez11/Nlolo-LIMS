package tz.co.nlolo.lifeplatform.accumulation;

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
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.application.AccumulationApiImpl;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * MALENGO -- a simulation of the savings plan the product owner described on 2026-10-15, run against a
 * real database through the real APIs, with every date backdated so a year of saving happens in one run.
 *
 * <p>The plan: 4% a year, deposit and withdraw whenever you like, no fixed sum assured, entry ages 18 to
 * 60, and taking the whole balance out ends the contract.
 *
 * <p><b>What this proves, and what it does not.</b> Everything here runs through the shipped engine --
 * the interest calculator, the month-end drain, the append-only ledger, the real two-person withdrawal.
 * The one rule it cannot show working is the last: emptying the account does NOT end the policy today,
 * and this records that gap rather than papering over it. Surrender is how a Malengo contract ends until
 * that is built.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class MalengoSimulationTest {

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
            "db-migrations/party/V7__kyc_decided_by_name.sql",
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
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/product/V30__funeral_group_rate_period.sql",
            "db-migrations/product/V31__online_listing.sql",
            "db-migrations/product/V32__account_charges.sql",
            "db-migrations/product/V33__term_account_rate_band.sql",
            "db-migrations/product/V34__version_account_charges.sql",
            "db-migrations/product/V35__product_description.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/accumulation/V6__withdrawal_payout_route.sql",
            "db-migrations/accumulation/V7__top_up_methods.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
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
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
            "db-migrations/underwriting/V20__sale_lock_backfill.sql",
            "db-migrations/underwriting/V21__case_account_charges.sql",
            "db-migrations/underwriting/V24__term_account_rate.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/policy/V38__group_funeral_scheme.sql",
            "db-migrations/policy/V40__commencement_never_null.sql",
            "db-migrations/policy/V41__policy_account_charges.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    private static final UUID TENANT = UUID.randomUUID();

    /**
     * The Malengo terms, as the product owner set them (2026-10-15).
     *
     * <p>4% guaranteed a year. Minimum balance ZERO, which is what lets a client take every shilling out
     * -- the usual savings plan keeps a floor so the account cannot be emptied, and Malengo deliberately
     * does not. Charges kept small for the demo: nothing held back from a deposit, and a 1,000 a month
     * policy fee.
     */
    static final AccumulationPlan MALENGO = new AccumulationPlan(ValueBasis.ACCOUNT,
        new BigDecimal("4"), BigDecimal.ZERO,
        List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000.00"))));

    /** Entry ages 18 to 60, on a 20-year term. */
    static final int MIN_ENTRY_AGE = 18;
    static final int MAX_ENTRY_AGE = 60;
    static final int TERM_MONTHS = 240;

    /** What the client puts in on day one. The sum assured MIRRORS it -- see openMalengoPolicy. */
    static final BigDecimal OPENING_DEPOSIT = new BigDecimal("500000.00");

    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private PolicyApi policyApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;

    private <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private record Opened(String policyNumber, UUID productId, UUID versionId, LocalDate commencement) {}

    /**
     * Publishes Malengo and sells one, commencing {@code monthsAgo} months ago on the 1st.
     *
     * <p><b>The sum assured mirrors the opening deposit.</b> Malengo has no fixed sum assured, but the
     * wire layer refuses a zero one, so the contract carries the deposit as its face amount -- exactly
     * the convention the fixed-term deposit already uses. It is a placeholder, not a benefit promise:
     * what a Malengo client is owed is the account balance, which is why the maturity payout row is
     * ACCOUNT_VALUE rather than a percentage of the sum assured.
     */
    private Opened openMalengoPolicy(int monthsAgo) {
        return asTenant(() -> {
            LocalDate commencement = LocalDate.now().withDayOfMonth(1).minusMonths(monthsAgo);
            // 35 years old at commencement: inside the 18-60 window.
            PartyView client = partyApi.registerIndividual("Malengo Demo Client",
                commencement.minusYears(35), "+255715000001", null, "test-agent");
            ProductSummaryView product = productApi.createProduct(
                "MALENGO-" + UUID.randomUUID().toString().substring(0, 6), "Malengo Savings Plan",
                // An account is a savings contract: the SAV portfolio, or publishing refuses it.
                ProductCategory.ENDOWMENT, PortfolioCode.SAV, "TZS", "actuary");
            PayoutPlan payout = PayoutPlan.authored(new PayoutTerms(15, null, null, null),
                List.of(new PayoutRowInput(PayoutKind.MATURITY, null, null,
                    // What the client gets is the account, not a multiple of a face amount.
                    PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal("100"), null)));
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(),
                new EligibilityBounds(MIN_ENTRY_AGE, MAX_ENTRY_AGE, null, null, null, null),
                FrequencyLoading.none(), ANY_FILING, CashValuePlan.none(), payout, MALENGO, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(client.partyId(), product.productId(),
                versionId, OPENING_DEPOSIT, "TZS", OPENING_DEPOSIT, "TZS", "MONTHLY", null,
                List.of(), "Malengo simulation", commencement, TERM_MONTHS, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return new Opened(policyNumber, product.productId(), versionId, commencement);
        });
    }

    private void deposit(String policyNumber, String amount, LocalDate on) {
        fixtures.collectPremium(TENANT, policyNumber, UUID.randomUUID(), new BigDecimal(amount), on);
    }

    /**
     * A real two-person withdrawal: one person asks, a second approves, the entry posts, and the payment
     * rail then confirms the money left.
     *
     * <p>The settlement is not decoration. A withdrawal sits REQUESTED or APPROVED until its disbursement
     * confirms, and the engine refuses a second withdrawal while one is in flight -- so without this the
     * client could never make their second withdrawal of the year. Found by running it.
     */
    private void withdraw(String policyNumber, String amount) {
        asTenant(() -> {
            WithdrawalView requested = api.requestWithdrawal(policyNumber, new BigDecimal(amount),
                "payee-malengo", "csr.one");
            api.approveWithdrawal(requested.withdrawalId(), "manager.two");
            api.settleWithdrawal(requested.withdrawalId(), UUID.randomUUID(), true);
            return null;
        });
    }

    private void runMonthEndsThrough(String policyNumber, LocalDate today) {
        asTenant(() -> { api.postMonthEnds(policyNumber, today); return null; });
    }

    private BigDecimal balance(String policyNumber) {
        return asTenant(() -> api.findAccount(policyNumber).orElseThrow().balance());
    }

    private List<LedgerEntryView> ledger(String policyNumber) {
        return asTenant(() -> api.entries(policyNumber));
    }

    private PolicyStatus policyStatus(String policyNumber) {
        return asTenant(() -> policyApi.getPolicy(policyNumber).status());
    }

    private static String line(String label, BigDecimal balance) {
        return String.format("  %-56s %16s", label, balance.toPlainString());
    }

    /**
     * A year of Malengo: open with 500,000, pay in twice more, take money out once, and let every month
     * end credit its 4%. Then empty the account, and show what that does and does not do.
     */
    @Test
    void aYearOfMalengoDepositsWithdrawalsAndInterest() {
        Opened opened = openMalengoPolicy(12);
        String policy = opened.policyNumber();
        LocalDate start = opened.commencement();
        List<String> story = new ArrayList<>();

        // Day one: the opening deposit.
        deposit(policy, OPENING_DEPOSIT.toPlainString(), start);
        runMonthEndsThrough(policy, start.plusMonths(3).minusDays(1));
        story.add(line("Opening deposit 500,000, then 2 month-ends", balance(policy)));

        // Month 3: pays in another 200,000.
        deposit(policy, "200000.00", start.plusMonths(3));
        runMonthEndsThrough(policy, start.plusMonths(6).minusDays(1));
        story.add(line("Top-up 200,000, then 3 more month-ends", balance(policy)));

        // Month 6: takes 150,000 out for school fees.
        withdraw(policy, "150000.00");
        runMonthEndsThrough(policy, start.plusMonths(9).minusDays(1));
        story.add(line("Withdrew 150,000, then 3 more month-ends", balance(policy)));

        // Month 9: pays in 300,000, then the year runs out.
        deposit(policy, "300000.00", start.plusMonths(9));
        runMonthEndsThrough(policy, LocalDate.now());
        BigDecimal afterAYear = balance(policy);
        story.add(line("Deposited 300,000, then the rest of the year", afterAYear));

        // Every movement is on the ledger, and nothing was edited: an append-only history.
        List<LedgerEntryView> entries = ledger(policy);
        assertThat(entries).extracting(LedgerEntryView::type)
            .contains(EntryType.CONTRIBUTION, EntryType.WITHDRAWAL, EntryType.INTEREST, EntryType.POLICY_FEE);

        // 1,000,000 paid in, 150,000 taken out, so 850,000 of bare cash. Interest at 4% must have beaten
        // twelve months of 1,000 fees. Asserted as a band, not a fixed figure: the exact total depends on
        // which month of the year the simulation runs in.
        BigDecimal cashIn = new BigDecimal("850000.00");
        assertThat(afterAYear).isGreaterThan(cashIn);
        assertThat(afterAYear).isLessThan(cashIn.add(new BigDecimal("40000.00")));
        assertThat(policyStatus(policy)).isEqualTo(PolicyStatus.ACTIVE);

        // The client now takes EVERYTHING. The minimum balance is zero, so the engine allows it.
        withdraw(policy, afterAYear.toPlainString());
        assertThat(balance(policy)).isEqualByComparingTo("0.00");
        story.add(line("Withdrew the whole balance", balance(policy)));

        // THE GAP, asserted so it cannot drift unnoticed. An empty account does NOT end the contract:
        // the policy is still ACTIVE and the account still OPEN. Only the month-end charge run closes an
        // account by exhaustion, and a client-driven withdrawal never takes that path.
        assertThat(policyStatus(policy)).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(asTenant(() -> api.findAccount(policy).orElseThrow().status())).isEqualTo(AccountStatus.OPEN);
        story.add("  (the policy is still ACTIVE on a zero balance -- see the gap in the guide)");

        System.out.println("\n=== MALENGO SIMULATION: " + policy + " ===");
        story.forEach(System.out::println);
        System.out.println("\n--- ledger ---");
        ledger(policy).forEach(e -> System.out.printf("  %s  %-14s %16s  %16s  %s%n",
            e.effectiveDate(), e.type(), e.amount().toPlainString(),
            e.balanceAfter().toPlainString(), e.reason() == null ? "" : e.reason()));
    }
}
