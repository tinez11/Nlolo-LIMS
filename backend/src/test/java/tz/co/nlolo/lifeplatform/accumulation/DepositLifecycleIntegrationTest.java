package tz.co.nlolo.lifeplatform.accumulation;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import tz.co.nlolo.lifeplatform.accumulation.application.DepositInterest;
import tz.co.nlolo.lifeplatform.accumulation.application.Deposits;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user's fixed-term deposit, end to end: the deposit opens its term at the grid's rate, nothing
 * moves during it, every early exit pays interest by the day once, and at maturity it is reinvested
 * at the rate then in force, paid to the number it came from, or kept for a payee.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class DepositLifecycleIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            DepositTestMigrations.ALL);
    }

    @AfterAll
    static void stopGateway() {
        wireMock.stop();
    }

    @BeforeEach
    void gatewayAccepts() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"GW-OK\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/collect")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"GW-OK\"}")));
    }

    private void forceTheGatewayToDecline() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final BigDecimal MILLION = new BigDecimal("1000000.00");
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private Deposits deposits;
    @Autowired private PolicyApi policyApi;
    @Autowired private BenefitPayoutApi benefitPayoutApi;
    @Autowired private BillingApi billingApi;
    @Autowired private JdbcTemplate jdbc;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }
    private DepositView deposit(String p) { return asTenant(() -> api.findDeposit(p)).orElseThrow(); }
    private List<LedgerEntryView> entriesOf(String p) { return asTenant(() -> api.entries(p)); }
    private static String key() { return UUID.randomUUID().toString(); }

    /** A deposit commenced and paid on {@code paidOn}. */
    private String paidDeposit(BigDecimal amount, int term, LocalDate paidOn, String payerRef) {
        var issued = fixtures.issueDeposit(TENANT, amount, term, paidOn);
        fixtures.collectDeposit(TENANT, issued.policyNumber(), UUID.randomUUID(), amount, paidOn, payerRef);
        return issued.policyNumber();
    }

    // ---- Opening and the term ------------------------------------------------------------------

    @Test
    void theDepositOpensItsFirstTermAtTheVersionsRateAndTheTermFollowsTheMoney() {
        LocalDate issued = LocalDate.now().minusDays(14);
        LocalDate paid = LocalDate.now().minusDays(10);
        var policy = fixtures.issueDeposit(TENANT, new BigDecimal("6000000.00"), 6, issued);
        fixtures.collectDeposit(TENANT, policy.policyNumber(), UUID.randomUUID(), new BigDecimal("6000000.00"), paid,
            "+255700000777");

        DepositPeriodView first = deposit(policy.policyNumber()).periods().get(0);
        assertThat(first.ratePercent()).isEqualByComparingTo("5"); // the 6,000,000 band, 6 months
        assertThat(first.principal()).isEqualByComparingTo("6000000.00");
        assertThat(first.startDate()).isEqualTo(paid);
        assertThat(first.maturityDate()).isEqualTo(paid.plusMonths(6));
        assertThat(first.status()).isEqualTo(DepositPeriodStatus.RUNNING);
        assertThat(deposit(policy.policyNumber()).defaultPayeeRef()).isEqualTo("+255700000777");
        // The money came four days after issue: the policy's term starts with it (plan R16).
        var view = asTenant(() -> policyApi.getPolicy(policy.policyNumber()));
        assertThat(view.commencementDate()).isEqualTo(paid);
        assertThat(view.maturityDate()).isEqualTo(paid.plusMonths(6));
        // One CONTRIBUTION and no allocation charge (a zero line is never written).
        assertThat(entriesOf(policy.policyNumber())).extracting(LedgerEntryView::type).containsExactly(EntryType.CONTRIBUTION);
        // Ten days of 5% on 6,000,000 over the term's days.
        long termDays = ChronoUnit.DAYS.between(paid, paid.plusMonths(6));
        assertThat(deposit(policy.policyNumber()).interestSoFar()).isEqualByComparingTo(new BigDecimal("3000000")
            .divide(BigDecimal.valueOf(termDays), 2, RoundingMode.HALF_EVEN));
    }

    @Test
    void aCollectionThroughBillingRecordsTheNumberItCameFrom() {
        var issued = fixtures.issueDeposit(TENANT, MILLION, 3, LocalDate.now());
        UUID invoiceId = asTenant(() -> billingApi.listInvoices(issued.policyNumber(), null)).get(0).invoiceId();
        // What payment publishes when the rail confirms the collection.
        fixtures.publish(TENANT, "payment.PaymentConfirmed", Map.of(
            "paymentRequestId", UUID.randomUUID(), "idempotencyKey", "k-" + invoiceId, "sourceRef", invoiceId.toString(),
            "gatewayReference", "GW-1", "amount", Map.of("amount", "1000000.00", "currencyCode", "TZS"),
            "confirmedAt", java.time.Instant.now().toString(), "purpose", "PREMIUM", "payerRef", "+255700000555"));
        assertThat(deposit(issued.policyNumber()).defaultPayeeRef()).isEqualTo("+255700000555");
        assertThat(deposit(issued.policyNumber()).periods()).hasSize(1);
    }

    @Test
    void nothingCanBeAddedOrTakenOutDuringTheTerm() {
        String policy = paidDeposit(MILLION, 3, LocalDate.now(), "+255700000777");
        String expected = "This is a fixed-term deposit: nothing can be added or taken out until it matures on "
            + LocalDate.now().plusMonths(3);
        assertThatThrownBy(() -> asTenant(() -> api.requestTopUp(policy, new BigDecimal("1000.00"), "+2557", "staff-one")))
            .hasMessage(expected);
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("1000.00"), "+2557", "staff-one")))
            .hasMessage(expected);
        assertThatThrownBy(() -> asTenant(() -> api.recordTransferIn(policy, new BigDecimal("1000.00"), "NSSF", null, "fin")))
            .hasMessage(expected);
    }

    @Test
    void theMonthEndRunPostsNothingOnADeposit() {
        LocalDate paid = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        String policy = paidDeposit(MILLION, 12, paid, "+255700000777");
        asTenant(() -> { api.postMonthEnds(policy, LocalDate.now()); return null; });
        assertThat(entriesOf(policy)).extracting(LedgerEntryView::type).containsExactly(EntryType.CONTRIBUTION);
    }

    // ---- Early exits ---------------------------------------------------------------------------

    @Test
    void anEarlyTerminationPaysTheDepositAndInterestByTheDayOnce() {
        LocalDate paid = LocalDate.now().minusDays(45);
        String policy = paidDeposit(MILLION, 3, paid, "+255700000777");
        BigDecimal expected = DepositInterest.accrued(MILLION, new BigDecimal("3"), paid, paid.plusMonths(3), LocalDate.now());
        var request = asTenant(() -> policyApi.requestSurrender(policy, "+255700000003", "staff-one"));
        asTenant(() -> policyApi.approveSurrender(request.surrenderRequestId(), "finance-two"));

        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all).filteredOn(e -> e.type() == EntryType.INTEREST).singleElement()
            .satisfies(e -> assertThat(e.amount()).isEqualByComparingTo(expected));
        assertThat(all.get(all.size() - 1).type()).isEqualTo(EntryType.SURRENDER);
        assertThat(all.get(all.size() - 1).amount().negate()).isEqualByComparingTo(MILLION.add(expected));
        DepositPeriodView term = deposit(policy).periods().get(0);
        assertThat(term.status()).isEqualTo(DepositPeriodStatus.TERMINATED);
        assertThat(term.interestPosted()).isEqualByComparingTo(expected);
    }

    @Test
    void aDeathIsValuedWithInterestToTheDateOfDeath() {
        LocalDate paid = LocalDate.now().minusDays(30);
        String policy = paidDeposit(MILLION, 3, paid, "+255700000777");
        LocalDate death = paid.plusDays(20);
        assertThat(asTenant(() -> api.valueAtDeath(policy, death)).accountValue())
            .isEqualByComparingTo(MILLION.add(DepositInterest.accrued(MILLION, new BigDecimal("3"), paid, paid.plusMonths(3), death)));
    }

    @Test
    void aDepositHasAScheduledMaturitySoNoMaturityClaimCanBeFiled() {
        String policy = paidDeposit(MILLION, 3, LocalDate.now(), "+255700000777");
        assertThat(asTenant(() -> benefitPayoutApi.hasScheduledMaturity(policy))).isTrue();
    }

    // ---- Maturity ------------------------------------------------------------------------------

    /** Paid three months and a day ago on a three-month term: matured yesterday or so. */
    private String maturedRecently(String payerRef) {
        return paidDeposit(MILLION, 3, LocalDate.now().minusDays(1).minusMonths(3), payerRef);
    }

    private void runMaturity(String policy) {
        asTenant(() -> { deposits.mature(policy, LocalDate.now()); return null; });
    }

    private Map<String, Object> disbursementFor(String policy) {
        return jdbc.queryForMap("SELECT purpose, payee_ref, amount, source_ref FROM payment.disbursement_instruction "
            + "WHERE source_ref LIKE ? ORDER BY source_ref DESC LIMIT 1", // ":2" sorts after ":1"
            deposit(policy).periods().get(0).periodId() + ":%");
    }

    @Test
    void withNoInstructionTheDepositIsPaidToTheNumberItCameFrom() {
        String policy = maturedRecently("+255700000777");
        runMaturity(policy);

        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all).extracting(LedgerEntryView::type)
            .containsExactly(EntryType.CONTRIBUTION, EntryType.INTEREST, EntryType.MATURITY);
        assertThat(all.get(1).amount()).isEqualByComparingTo("30000.00");
        assertThat(all.get(1).sourceRef()).isEqualTo("deposit-interest:" + deposit(policy).periods().get(0).periodId());
        assertThat(all.get(2).amount()).isEqualByComparingTo("-1030000.00");
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().closedReason()).isEqualTo("MATURED");
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.MATURED);
        Map<String, Object> paid = disbursementFor(policy);
        assertThat(paid.get("purpose")).isEqualTo("DEPOSIT_MATURITY_PAYOUT");
        assertThat(paid.get("payee_ref")).isEqualTo("+255700000777");
        assertThat((BigDecimal) paid.get("amount")).isEqualByComparingTo("1030000.00");
        // Running it again moves nothing: the term no longer RUNS.
        runMaturity(policy);
        assertThat(entriesOf(policy)).hasSize(3);
    }

    @Test
    void reinvestedForANewTermAtTheRateOfTheVersionInForceAtMaturity() {
        String policy = maturedRecently("+255700000777");
        UUID productId = asTenant(() -> api.findAccount(policy)).orElseThrow().productId();
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 6, null, "staff-one", key()));
        // New rates published AFTER the first term started; the reinvestment must use them (D3, D8).
        UUID newVersion = fixtures.publishDepositVersion(TENANT, productId, AccumulationTestFixtures.grid(new String[][] {
            {"500000", "9", "10", "11"}, {"6000000", "4", "5", "6"}, {"11000000", "5", "6", "7"}, {"21000000", "6", "7", "8"}}));
        runMaturity(policy);

        DepositView d = deposit(policy);
        assertThat(d.periods()).hasSize(2);
        DepositPeriodView first = d.periods().get(0);
        DepositPeriodView second = d.periods().get(1);
        assertThat(first.status()).isEqualTo(DepositPeriodStatus.MATURED);
        assertThat(second.principal()).isEqualByComparingTo("1030000.00"); // the deposit plus its interest (D6)
        assertThat(second.termMonths()).isEqualTo(6);
        assertThat(second.ratePercent()).isEqualByComparingTo("10");
        assertThat(second.rateVersionId()).isEqualTo(newVersion);
        assertThat(second.startDate()).isEqualTo(first.maturityDate());
        // The policy's term grew by six months from the same commencement (plan R16).
        var view = asTenant(() -> policyApi.getPolicy(policy));
        assertThat(view.policyTermMonths()).isEqualTo(9);
        assertThat(view.maturityDate()).isEqualTo(second.maturityDate()).isEqualTo(view.commencementDate().plusMonths(9));
        assertThat(view.status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void aReinvestmentForATermNoLongerOfferedIsPaidOutAndSaysWhy() {
        String policy = maturedRecently("+255700000777");
        UUID productId = asTenant(() -> api.findAccount(policy)).orElseThrow().productId();
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 12, null, "staff-one", key()));
        fixtures.publishDepositVersion(TENANT, productId, new DepositPlan(List.of(
            new DepositRateRow(new BigDecimal("500000"), 3, new BigDecimal("3")))));
        runMaturity(policy);
        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all.get(all.size() - 1).type()).isEqualTo(EntryType.MATURITY);
        assertThat(all.get(all.size() - 1).reason()).contains("no longer offered");
    }

    @Test
    void withNoNumberTheMoneyWaitsForStaffToRecordAPayee() {
        String policy = maturedRecently(null);
        runMaturity(policy);
        assertThat(deposit(policy).awaitingPayee()).isTrue();
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("1030000.00");
        assertThat(asTenant(() -> api.listAwaitingPayee())).extracting(AwaitingPayeeView::policyNumber).contains(policy);

        asTenant(() -> api.payOutMaturedDeposit(policy, "+255700000888", "finance-one", key()));
        assertThat(deposit(policy).awaitingPayee()).isFalse();
        assertThat(disbursementFor(policy).get("payee_ref")).isEqualTo("+255700000888");
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.MATURED);
        assertThat(asTenant(() -> api.listAwaitingPayee())).extracting(AwaitingPayeeView::policyNumber).doesNotContain(policy);
    }

    @Test
    void aFailedMaturityPaymentPutsTheMoneyBackAndWaitsForAPayee() {
        forceTheGatewayToDecline();
        String policy = maturedRecently("+255700000777");
        runMaturity(policy);
        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all.get(all.size() - 1).type()).isEqualTo(EntryType.REVERSAL);
        assertThat(deposit(policy).awaitingPayee()).isTrue();
        // The second attempt has its own reference, so the ledger's once-only index lets it through.
        gatewayAccepts();
        asTenant(() -> api.payOutMaturedDeposit(policy, "+255700000999", "finance-one", key()));
        assertThat((String) disbursementFor(policy).get("source_ref")).endsWith(":2");
    }

    @Test
    void anInstructionCanBeChangedUntilMaturityAndTheHistoryIsKept() {
        String policy = paidDeposit(MILLION, 3, LocalDate.now(), "+255700000777");
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 6, null, "staff-one", key()));
        asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.PAY_OUT, null, "+255700000123", "staff-two", key()));
        assertThat(deposit(policy).instruction().action()).isEqualTo(MaturityAction.PAY_OUT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accumulation.maturity_instruction WHERE policy_number = ?",
            Integer.class, policy)).isEqualTo(2);
        assertThatThrownBy(() -> asTenant(() -> api.recordMaturityInstruction(policy, MaturityAction.REINVEST, 9, null, "s", key())))
            .hasMessageContaining("offers terms of [3, 6, 12] months");
    }
}
