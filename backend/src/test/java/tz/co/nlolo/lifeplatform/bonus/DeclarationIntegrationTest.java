package tz.co.nlolo.lifeplatform.bonus;

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
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;
import tz.co.nlolo.lifeplatform.bonus.api.DeclarationStatus;
import tz.co.nlolo.lifeplatform.bonus.application.BonusApiImpl;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Declarations: two people, one approved per product and date, a proposal withdrawable, once per key. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class DeclarationIntegrationTest {

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
    @Autowired private BonusTestFixtures fixtures;
    @Autowired private BonusApiImpl api;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private UUID withProfitsProduct() {
        return fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none()).productId();
    }

    @Test
    void theProposerCannotApproveAndASecondPersonCan() {
        UUID product = withProfitsProduct();
        var proposed = asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), new BigDecimal("3.5"),
            new BigDecimal("40"), "admin-one"));
        assertThatThrownBy(() -> asTenant(() -> api.approveDeclaration(proposed.declarationId(), "admin-one")))
            .isInstanceOf(BonusStateException.class)
            .hasMessage("A bonus declaration must be approved by someone other than the person who proposed it");
        assertThat(asTenant(() -> api.approveDeclaration(proposed.declarationId(), "finance-two")).status())
            .isEqualTo(DeclarationStatus.APPROVED);
    }

    @Test
    void oneApprovedDeclarationPerProductAndValuationDate() {
        UUID product = withProfitsProduct();
        LocalDate date = LocalDate.now().plusMonths(2);
        var first = asTenant(() -> api.proposeDeclaration(product, date, BigDecimal.ONE, BigDecimal.ZERO, "admin-one"));
        var second = asTenant(() -> api.proposeDeclaration(product, date, BigDecimal.TWO, BigDecimal.ZERO, "admin-one"));
        asTenant(() -> api.approveDeclaration(first.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.approveDeclaration(second.declarationId(), "finance-two")))
            .isInstanceOf(BonusStateException.class)
            .hasMessage("A bonus is already declared for this product as at " + date + ". An approved declaration cannot be withdrawn.");
    }

    @Test
    void onlyAProposalCanBeWithdrawn() {
        UUID product = withProfitsProduct();
        var d = asTenant(() -> api.proposeDeclaration(product, LocalDate.now().plusMonths(3), BigDecimal.ONE, BigDecimal.ZERO, "admin-one"));
        asTenant(() -> api.approveDeclaration(d.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.withdrawDeclaration(d.declarationId(), "admin-one")))
            .hasMessage("This bonus declaration is approved, not awaiting approval");
    }

    @Test
    void ratesOutsideTheirRangesAreRefused() {
        UUID product = withProfitsProduct();
        assertThatThrownBy(() -> asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), new BigDecimal("101"),
            BigDecimal.ZERO, "admin-one"))).hasMessage("A reversionary bonus rate must be between 0% and 100%");
        assertThatThrownBy(() -> asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), BigDecimal.ONE,
            new BigDecimal("1001"), "admin-one"))).hasMessage("A terminal bonus rate must be between 0% and 1000% of attached bonuses");
    }

    @Test
    void aProductWithNoWithProfitsVersionCannotDeclare() {
        UUID product = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none()).productId();
        assertThatThrownBy(() -> asTenant(() -> api.proposeDeclaration(product, LocalDate.now(), BigDecimal.ONE,
            BigDecimal.ZERO, "admin-one"))).hasMessage("This product has no with-profits policies to declare a bonus on");
    }

    @Test
    void theSameProposalSentTwiceWithOneKeyIsMadeOnce() {
        UUID product = withProfitsProduct();
        String key = UUID.randomUUID().toString();
        LocalDate date = LocalDate.now().plusMonths(4);
        var first = asTenant(() -> api.proposeDeclaration(product, date, BigDecimal.ONE, BigDecimal.ZERO, "admin-one", key));
        var again = asTenant(() -> api.proposeDeclaration(product, date, BigDecimal.ONE, BigDecimal.ZERO, "admin-one", key));
        assertThat(again.declarationId()).isEqualTo(first.declarationId());
        assertThat(asTenant(() -> api.listDeclarations(product))).hasSize(1);
    }
}
