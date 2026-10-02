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
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What policy holds a fixed-term deposit to (plan §R9, R8, R10): one payment, of the deposit itself,
 * for a term the grid offers; a maturity date that can follow the money; and never expired by the
 * sweep or made paid-up.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class DepositIssuanceIntegrationTest {

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
            DepositTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private PolicyApi.IssueRequest request(AccumulationTestFixtures.Issued on, String sa, String premium, String freq, Integer term) {
        var policy = asTenant(() -> policyApi.getPolicy(on.policyNumber()));
        return new PolicyApi.IssueRequest(policy.policyholderPartyId(), on.productId(), on.productVersionId(),
            new BigDecimal(sa), "TZS", new BigDecimal(premium), "TZS", freq, null, List.of(), "deposit test",
            LocalDate.now(), term, null, null, null);
    }

    private void refused(PolicyApi.IssueRequest r, String message) {
        assertThatThrownBy(() -> asTenant(() -> policyApi.issuePolicy(UUID.randomUUID(), r, "test-staff")))
            .hasMessageContaining(message);
    }

    @Test
    void aDepositIsIssuedAsOnePaymentOfTheDepositForAnOfferedTerm() {
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now());
        var policy = asTenant(() -> policyApi.getPolicy(issued.policyNumber()));
        assertThat(policy.premiumFrequency()).isEqualTo("SINGLE");
        assertThat(policy.maturityDate()).isEqualTo(LocalDate.now().plusMonths(3));

        refused(request(issued, "1000000.00", "1000000.00", "MONTHLY", 3), "must be SINGLE");
        refused(request(issued, "1000000.00", "50000.00", "SINGLE", 3), "must equal the sum assured");
        refused(request(issued, "1000000.00", "1000000.00", "SINGLE", 9), "offers terms of [3, 6, 12] months");
        refused(request(issued, "400000.00", "400000.00", "SINGLE", 3), "below the smallest band this product offers (500000)");
    }

    @Test
    void theTermFollowsTheMoneyAndGrowsOnReinvestmentButNotOnAClosedPolicy() {
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now());
        // The money arrived four days after issue: commencement moves to it, the term is unchanged.
        LocalDate paid = LocalDate.now().plusDays(4);
        assertThat(asTenant(() -> policyApi.restateDepositTerm(issued.policyNumber(), paid, 3))).isEqualTo(paid.plusMonths(3));
        // Reinvested for six more months: commencement stays, the months grow, maturity is derived.
        assertThat(asTenant(() -> policyApi.restateDepositTerm(issued.policyNumber(), paid, 9))).isEqualTo(paid.plusMonths(9));
        var policy = asTenant(() -> policyApi.getPolicy(issued.policyNumber()));
        assertThat(policy.commencementDate()).isEqualTo(paid);
        assertThat(policy.policyTermMonths()).isEqualTo(9);
        assertThat(policy.maturityDate()).isEqualTo(paid.plusMonths(9));

        asTenant(() -> { policyApi.markMatured(issued.policyNumber(), "test"); return null; });
        assertThatThrownBy(() -> asTenant(() -> policyApi.restateDepositTerm(issued.policyNumber(), paid, 12)))
            .hasMessageContaining("is closed");
    }

    @Test
    void theExpirySweepNeverExpiresADepositAndItCannotBeMadePaidUp() {
        // Commenced three months and a day ago on a three-month term: past its maturity date.
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now().minusMonths(3).minusDays(1));
        asTenant(() -> { policyApi.expirePolicy(issued.policyNumber()); return null; });
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.ACTIVE);
        assertThatThrownBy(() -> asTenant(() -> policyApi.makePaidUp(issued.policyNumber(), "staff-one")))
            .hasMessageContaining("fixed-term deposit");
    }
}
