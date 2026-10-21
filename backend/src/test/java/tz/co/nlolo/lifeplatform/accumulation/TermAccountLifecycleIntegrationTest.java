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
import tz.co.nlolo.lifeplatform.accumulation.application.InterestCalculator;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalAmendmentApi;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The term account (2026-10-21), end to end: a case names its rate inside the version's band, the policy is issued at
 * it and keeps it whatever the product later declares, money comes in as on any savings account but nothing leaves
 * before maturity, and at maturity the whole balance is paid to the number it came from -- or waits for a payee.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class TermAccountLifecycleIntegrationTest {

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
            Stream.concat(Stream.of(DepositTestMigrations.ALL), Stream.of(
                "db-migrations/accumulation/V5__term_accounts.sql",
                "db-migrations/payment/V15__term_account_maturity_purpose.sql",
                "db-migrations/underwriting/V22__accepted_quotation.sql",
                "db-migrations/underwriting/V23__return_for_amendment.sql")).toArray(String[]::new));
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
    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal CONTRIBUTION = new BigDecimal("50000.00");
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private PolicyApi policyApi;
    @Autowired private ProductApi productApi;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private ProposalAmendmentApi amendments;
    @Autowired private JdbcTemplate jdbc;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }
    private TermAccountView termAccount(String p) { return asTenant(() -> api.findTermAccount(p)).orElseThrow(); }
    private List<LedgerEntryView> entriesOf(String p) { return asTenant(() -> api.entries(p)); }
    private AccountView account(String p) { return asTenant(() -> api.findAccount(p)).orElseThrow(); }
    private static String key() { return UUID.randomUUID().toString(); }

    /** A term account at 5% for {@code months}, commenced and paid into on {@code commenced}. */
    private String paidTermAccount(int months, LocalDate commenced, String payerRef) {
        var issued = fixtures.issueTermAccount(TENANT, FIVE, months, commenced);
        fixtures.collectDeposit(TENANT, issued.policyNumber(), UUID.randomUUID(), CONTRIBUTION, commenced, payerRef);
        return issued.policyNumber();
    }

    private ProposalDetails proposal(Integer term, BigDecimal rate) {
        return new ProposalDetails(null, null, null, LocalDate.now(), term, null, "MONTHLY", List.of(), rate);
    }

    private UUID openCase(AccumulationTestFixtures.Issued product, Integer term, BigDecimal rate) {
        UUID applicant = fixtures.applicant(TENANT);
        return asTenant(() -> underwritingApi.openCase(applicant, product.productId(), product.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, proposal(term, rate), "csr")).caseId();
    }

    // ---- The product and the case ---------------------------------------------------------------

    @Test
    void aVersionWithARateBandIsATermAccountAndNeedsNoMaturityRow() {
        var product = fixtures.termAccountProduct(TENANT);
        var plan = asTenant(() -> productApi.resolveAccumulationPlan(product.productVersionId()));
        assertThat(plan.isTermAccount()).isTrue();
        assertThat(plan.minRatePercent()).isEqualByComparingTo("3");
        assertThat(plan.maxRatePercent()).isEqualByComparingTo("7");
        // An ordinary savings version is not one.
        var savings = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        assertThat(asTenant(() -> productApi.resolveAccumulationPlan(savings.productVersionId())).isTermAccount()).isFalse();
    }

    @Test
    void aCaseNamesItsRateInsideTheBandAndATerm() {
        var product = fixtures.termAccountProduct(TENANT);
        assertThatThrownBy(() -> openCase(product, 24, new BigDecimal("9")))
            .hasMessage("This product offers a rate between 3% and 7%, not 9%");
        assertThatThrownBy(() -> openCase(product, 24, new BigDecimal("2.5")))
            .hasMessage("This product offers a rate between 3% and 7%, not 2.5%");
        assertThatThrownBy(() -> openCase(product, 24, null)).hasMessageContaining("needs the rate it is sold at");
        assertThatThrownBy(() -> openCase(product, null, FIVE)).hasMessageContaining("needs a term");
        // The version's term bounds, the rule every product's term already meets.
        assertThatThrownBy(() -> openCase(product, 6, FIVE)).hasMessageContaining("below this product's minimum of 12");
        // Both ends of the band are offered.
        UUID atMin = openCase(product, 12, new BigDecimal("3"));
        UUID atMax = openCase(product, 60, new BigDecimal("7.0"));
        assertThat(asTenant(() -> underwritingApi.getCase(atMin)).proposedRatePercent()).isEqualByComparingTo("3");
        assertThat(asTenant(() -> underwritingApi.getCase(atMax)).proposedRatePercent()).isEqualByComparingTo("7");
    }

    @Test
    void aRateIsRefusedOnAProductThatIsNotATermAccount() {
        var savings = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        assertThatThrownBy(() -> openCase(savings, 24, FIVE)).hasMessageContaining("leave the rate blank");
    }

    @Test
    void anAmendedProposalIsHeldToTheBandAsItsOpeningWas() {
        var product = fixtures.termAccountProduct(TENANT);
        UUID caseId = openCase(product, 24, FIVE);
        asTenant(() -> amendments.returnForAmendment(caseId, List.of(new ProposalAmendmentApi.ReturnReason(
            ProposalAmendmentApi.ReturnCategory.COVER_OR_TERM, "Rate too low", "Raise it")), null, "uw-one"));
        assertThatThrownBy(() -> asTenant(() -> amendments.amendTerms(caseId, new ProposalAmendmentApi.TermsAmendment(
                new BigDecimal("1000000"), 24, null, "MONTHLY", LocalDate.now(), new BigDecimal("7.5")), "agent-one")))
            .hasMessage("This product offers a rate between 3% and 7%, not 7.5%");
        var amended = asTenant(() -> amendments.amendTerms(caseId, new ProposalAmendmentApi.TermsAmendment(
            new BigDecimal("1000000"), 24, null, "MONTHLY", LocalDate.now(), new BigDecimal("6")), "agent-one"));
        assertThat(amended.proposedRatePercent()).isEqualByComparingTo("6");
    }

    @Test
    void issuanceRefusesATermAccountWithoutItsCasesRate() {
        var product = fixtures.termAccountProduct(TENANT);
        UUID holder = fixtures.applicant(TENANT);
        var request = new PolicyApi.IssueRequest(holder, product.productId(), product.productVersionId(),
            new BigDecimal("1000000.00"), "TZS", CONTRIBUTION, "TZS", "MONTHLY", null, List.of(), "test",
            LocalDate.now(), 24, null, null, null);
        assertThatThrownBy(() -> asTenant(() -> policyApi.issuePolicy(UUID.randomUUID(), request, "staff")))
            .hasMessageContaining("this case names none");
        var noTerm = new PolicyApi.IssueRequest(holder, product.productId(), product.productVersionId(),
            new BigDecimal("1000000.00"), "TZS", CONTRIBUTION, "TZS", "MONTHLY", null, List.of(), "test",
            LocalDate.now(), null, null, null, null);
        assertThatThrownBy(() -> asTenant(() -> policyApi.issuePolicy(UUID.randomUUID(), noTerm, "staff")))
            .hasMessageContaining("needs a term");
    }

    // ---- Opening and the term ------------------------------------------------------------------

    @Test
    void theAccountOpensAtTheCasesRateAndMaturesWithThePolicy() {
        LocalDate commenced = LocalDate.now().minusDays(3);
        var issued = fixtures.issueTermAccount(TENANT, new BigDecimal("4.25"), 24, commenced);
        TermAccountView term = termAccount(issued.policyNumber());
        assertThat(term.ratePercent()).isEqualByComparingTo("4.25");
        assertThat(term.maturityDate()).isEqualTo(commenced.plusMonths(24))
            .isEqualTo(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).maturityDate());
        assertThat(term.status()).isEqualTo(TermAccountStatus.RUNNING);
        assertThat(term.defaultPayeeRef()).isNull();
        // The first contribution's number is where the maturity will go.
        fixtures.collectDeposit(TENANT, issued.policyNumber(), UUID.randomUUID(), CONTRIBUTION, commenced, "+255700000777");
        fixtures.collectDeposit(TENANT, issued.policyNumber(), UUID.randomUUID(), CONTRIBUTION, commenced, "+255700000111");
        assertThat(termAccount(issued.policyNumber()).defaultPayeeRef()).isEqualTo("+255700000777");
        assertThat(account(issued.policyNumber()).balance()).isEqualByComparingTo("100000.00");
        // The locked rate is the record of the sale: the database refuses to change it.
        assertThatThrownBy(() -> jdbc.update("UPDATE accumulation.term_account SET rate_percent = 9 WHERE policy_number = ?",
            issued.policyNumber())).hasMessageContaining("is the record of its rate");
    }

    @Test
    void nothingLeavesBeforeMaturityButMoneyStillComesIn() {
        String policy = paidTermAccount(24, LocalDate.now(), "+255700000777");
        String locked = "This account is locked until it matures on " + LocalDate.now().plusMonths(24);
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("1000.00"), "+2557", "staff-one")))
            .hasMessage(locked);
        assertThatThrownBy(() -> asTenant(() -> policyApi.requestSurrender(policy, "+255700000003", "staff-one")))
            .hasMessage(locked);
        // Money in: a top-up (the test rail confirms it at once, so it is credited) and a transfer in.
        asTenant(() -> api.requestTopUp(policy, new BigDecimal("1000.00"), "+255700000777", "staff-one"));
        asTenant(() -> api.recordTransferIn(policy, new BigDecimal("2000.00"), "NSSF", null, "finance-one"));
        assertThat(account(policy).balance()).isEqualByComparingTo("53000.00");
        assertThat(entriesOf(policy)).extracting(LedgerEntryView::type)
            .contains(EntryType.TOP_UP, EntryType.TRANSFER_IN);
    }

    @Test
    void interestIsCreditedAtTheLockedRateNotTheProductsDeclaration() {
        LocalDate commenced = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        String policy = paidTermAccount(24, commenced, "+255700000777");
        UUID productId = account(policy).productId();
        // The product declares 8% from the day this policy started. It must change nothing here.
        var declared = asTenant(() -> api.proposeRate(productId, new BigDecimal("8"), commenced, "actuary-one"));
        asTenant(() -> api.approveRate(declared.declarationId(), "actuary-two"));
        asTenant(() -> { api.postMonthEnds(policy, LocalDate.now()); return null; });

        LocalDate firstMonthEnd = commenced.withDayOfMonth(commenced.lengthOfMonth());
        BigDecimal atFive = InterestCalculator.interest(BigDecimal.ZERO,
            List.of(new InterestCalculator.Movement(commenced, CONTRIBUTION)), commenced, firstMonthEnd, d -> FIVE)
            .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal atEight = InterestCalculator.interest(BigDecimal.ZERO,
            List.of(new InterestCalculator.Movement(commenced, CONTRIBUTION)), commenced, firstMonthEnd, d -> new BigDecimal("8"))
            .setScale(2, RoundingMode.HALF_EVEN);
        List<LedgerEntryView> interest = entriesOf(policy).stream().filter(e -> e.type() == EntryType.INTEREST).toList();
        assertThat(interest).hasSize(2);
        assertThat(interest.get(0).amount()).isEqualByComparingTo(atFive).isNotEqualByComparingTo(atEight);
    }

    @Test
    void aMissedContributionDoesNotLapseIt() {
        var issued = fixtures.issueTermAccount(TENANT, FIVE, 24, LocalDate.now().minusMonths(4));
        fixtures.publish(TENANT, "billing.PolicyLapseRecommended", Map.of("policyNumber", issued.policyNumber()));
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void aDeathIsValuedAsAnyAccountsIsAtTheLockedRate() {
        LocalDate commenced = LocalDate.now().minusDays(40);
        String policy = paidTermAccount(24, commenced, "+255700000777");
        LocalDate death = commenced.plusDays(20);
        BigDecimal expected = CONTRIBUTION.add(InterestCalculator.interest(BigDecimal.ZERO,
            List.of(new InterestCalculator.Movement(commenced, CONTRIBUTION)), commenced, death, d -> FIVE)
            .setScale(2, RoundingMode.HALF_EVEN));
        assertThat(asTenant(() -> api.valueAtDeath(policy, death)).accountValue()).isEqualByComparingTo(expected);
    }

    // ---- Maturity ------------------------------------------------------------------------------

    /** Twelve months on a twelve-month term, commenced a year and a day ago: matured yesterday. */
    private String maturedYesterday(String payerRef) {
        return paidTermAccount(12, LocalDate.now().minusDays(1).minusMonths(12), payerRef);
    }

    private void runMaturity(String policy) {
        asTenant(() -> { api.matureTermAccount(policy, LocalDate.now()); return null; });
    }

    private Map<String, Object> disbursementFor(String policy) {
        return jdbc.queryForMap("SELECT purpose, payee_ref, amount, source_ref FROM payment.disbursement_instruction "
            + "WHERE source_ref LIKE ? ORDER BY source_ref DESC LIMIT 1", policy + ":%");
    }

    @Test
    void theExpirySweepNeverExpiresATermAccount() {
        String policy = maturedYesterday("+255700000777");
        asTenant(() -> { policyApi.expirePolicy(policy); return null; });
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void atMaturityTheWholeBalanceIsPaidToTheNumberItCameFrom() {
        String policy = maturedYesterday("+255700000777");
        runMaturity(policy);

        List<LedgerEntryView> all = entriesOf(policy);
        LedgerEntryView last = all.get(all.size() - 1);
        assertThat(last.type()).isEqualTo(EntryType.MATURITY);
        // Every month-end before it, then the days from the last one to the maturity day, at the locked rate, once.
        assertThat(all).filteredOn(e -> ("term-account-interest:" + policy).equals(e.sourceRef())).hasSize(1);
        LocalDate maturity = termAccount(policy).maturityDate();
        LocalDate commenced = maturity.minusMonths(12);
        BigDecimal interest = all.stream().filter(e -> e.type() == EntryType.INTEREST).map(LedgerEntryView::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal wholeTerm = InterestCalculator.interest(BigDecimal.ZERO,
            List.of(new InterestCalculator.Movement(commenced, CONTRIBUTION)), commenced, maturity, d -> FIVE);
        // Posted month by month, each rounded once; the whole term's figure, unrounded, is within a cent a month.
        assertThat(interest.subtract(wholeTerm).abs()).isLessThan(new BigDecimal("0.13"));
        assertThat(last.amount().negate()).isEqualByComparingTo(CONTRIBUTION.add(interest));

        assertThat(account(policy).closedReason()).isEqualTo("MATURED");
        assertThat(account(policy).balance()).isEqualByComparingTo("0.00");
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.MATURED);
        assertThat(termAccount(policy).status()).isEqualTo(TermAccountStatus.MATURED);
        Map<String, Object> paid = disbursementFor(policy);
        assertThat(paid.get("purpose")).isEqualTo("TERM_ACCOUNT_MATURITY_PAYOUT");
        assertThat(paid.get("payee_ref")).isEqualTo("+255700000777");
        assertThat((BigDecimal) paid.get("amount")).isEqualByComparingTo(CONTRIBUTION.add(interest));
        // Running it again moves nothing: it no longer RUNS.
        runMaturity(policy);
        assertThat(entriesOf(policy)).hasSize(all.size());
    }

    @Test
    void theMaturityRunFindsItAcrossTenants() {
        String policy = maturedYesterday("+255700000777");
        assertThat(jdbc.queryForList("SELECT policy_number FROM accumulation.term_accounts_due()", String.class))
            .contains(policy);
        runMaturity(policy);
        assertThat(jdbc.queryForList("SELECT policy_number FROM accumulation.term_accounts_due()", String.class))
            .doesNotContain(policy);
    }

    @Test
    void withNoNumberTheMoneyWaitsForFinanceToRecordAPayee() {
        String policy = maturedYesterday(null);
        runMaturity(policy);
        assertThat(termAccount(policy).status()).isEqualTo(TermAccountStatus.AWAITING_PAYEE);
        BigDecimal waiting = account(policy).balance();
        assertThat(waiting).isGreaterThan(CONTRIBUTION);
        assertThat(asTenant(() -> api.listTermAccountsAwaitingPayee())).extracting(AwaitingPayeeView::policyNumber)
            .contains(policy);
        // It earns nothing more while it waits, and nothing leaves but the payout.
        asTenant(() -> { api.postMonthEnds(policy, LocalDate.now().plusMonths(2)); return null; });
        assertThat(account(policy).balance()).isEqualByComparingTo(waiting);
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("1000.00"), "+2557", "s")))
            .hasMessageContaining("is paid out to a payee, not withdrawn");

        asTenant(() -> api.payOutMaturedTermAccount(policy, "+255700000888", "finance-one", key()));
        assertThat(termAccount(policy).status()).isEqualTo(TermAccountStatus.MATURED);
        assertThat(disbursementFor(policy).get("payee_ref")).isEqualTo("+255700000888");
        assertThat((BigDecimal) disbursementFor(policy).get("amount")).isEqualByComparingTo(waiting);
        assertThat(asTenant(() -> policyApi.getPolicy(policy)).status()).isEqualTo(PolicyStatus.MATURED);
        assertThat(asTenant(() -> api.listTermAccountsAwaitingPayee())).extracting(AwaitingPayeeView::policyNumber)
            .doesNotContain(policy);
    }

    @Test
    void aFailedMaturityPaymentPutsTheMoneyBackAndWaitsForAPayee() {
        forceTheGatewayToDecline();
        String policy = maturedYesterday("+255700000777");
        runMaturity(policy);
        List<LedgerEntryView> all = entriesOf(policy);
        assertThat(all.get(all.size() - 1).type()).isEqualTo(EntryType.REVERSAL);
        assertThat(termAccount(policy).status()).isEqualTo(TermAccountStatus.AWAITING_PAYEE);
        assertThat(account(policy).status()).isEqualTo(AccountStatus.OPEN);
        // The second attempt has its own reference, so the ledger's once-only index lets it through.
        gatewayAccepts();
        asTenant(() -> api.payOutMaturedTermAccount(policy, "+255700000999", "finance-one", key()));
        assertThat((String) disbursementFor(policy).get("source_ref")).isEqualTo(policy + ":2");
        assertThat(termAccount(policy).status()).isEqualTo(TermAccountStatus.MATURED);
    }
}
