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
import tz.co.nlolo.lifeplatform.accumulation.api.AccountStatus;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestMigrations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A pension's account closing for vesting (product step 5 D2, Task 3): interest to the day, then one
 * VESTING entry that empties it; once only; and a locked pension refusing withdrawals before it vests.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AnnuityTestFixtures.class)
class VestingCloseIntegrationTest {

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
            AnnuityTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = AnnuityTestFixtures.TODAY;

    @Autowired private AnnuityTestFixtures fixtures;
    @Autowired private AccumulationApi accumulationApi;

    /** A pension with three contributions of 200,000.00 collected today. */
    private String fundedPension(boolean surrenderBeforeVesting) {
        var product = fixtures.publishDeferred(TENANT, surrenderBeforeVesting);
        String policy = fixtures.issueDeferred(TENANT, product, fixtures.personBorn(TENANT, LocalDate.of(1980, 6, 15), null), 60);
        for (int i = 0; i < 3; i++) {
            fixtures.collect(TENANT, policy, "200000.00", TODAY);
        }
        return policy;
    }

    private List<LedgerEntryView> entriesOf(String policy) {
        return asTenant(TENANT, () -> accumulationApi.entries(policy));
    }

    @Test
    void anAccountClosesForVestingWithInterestToTheDayAndOneVestingEntry() {
        String policy = fundedPension(false);
        BigDecimal before = asTenant(TENANT, () -> accumulationApi.findAccount(policy)).orElseThrow().balance();
        assertThat(before).isEqualByComparingTo("600000.00");

        LocalDate vestingDate = TODAY.plusDays(30);
        BigDecimal vested = asTenant(TENANT, () -> accumulationApi.closeForVesting(policy, vestingDate));

        List<LedgerEntryView> entries = entriesOf(policy);
        LedgerEntryView interest = entries.get(entries.size() - 2);
        LedgerEntryView closing = entries.get(entries.size() - 1);
        assertThat(interest.type()).isEqualTo(EntryType.INTEREST);
        assertThat(interest.effectiveDate()).isEqualTo(vestingDate);
        assertThat(closing.type()).isEqualTo(EntryType.VESTING);
        assertThat(closing.effectiveDate()).isEqualTo(vestingDate);
        assertThat(closing.balanceAfter()).isEqualByComparingTo("0.00");
        assertThat(closing.amount().negate()).isEqualByComparingTo(vested);
        assertThat(vested).isEqualByComparingTo(before.add(interest.amount()));
        assertThat(interest.amount()).isPositive();

        var account = asTenant(TENANT, () -> accumulationApi.findAccount(policy)).orElseThrow();
        assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.closedReason()).isEqualTo("VESTED");
        assertThat(account.closedOn()).isEqualTo(vestingDate);
    }

    @Test
    void askedAgainItReturnsTheSameFigureAndPostsNothing() {
        String policy = fundedPension(false);
        BigDecimal first = asTenant(TENANT, () -> accumulationApi.closeForVesting(policy, TODAY.plusDays(30)));
        int entries = entriesOf(policy).size();
        BigDecimal second = asTenant(TENANT, () -> accumulationApi.closeForVesting(policy, TODAY.plusDays(31)));
        assertThat(second).isEqualByComparingTo(first);
        assertThat(entriesOf(policy)).hasSize(entries);
    }

    @Test
    void aLockedPensionRefusesAWithdrawalBeforeItVests() {
        String policy = fundedPension(false);
        assertThatThrownBy(() -> asTenant(TENANT, () -> accumulationApi.requestWithdrawal(policy, new BigDecimal("1000.00"),
                "+255700000001", "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("This pension cannot be surrendered or withdrawn from before it vests");
    }

    @Test
    void anUnlockedDeferredAnnuityAcceptsAWithdrawal() {
        String policy = fundedPension(true);
        var withdrawal = asTenant(TENANT, () -> accumulationApi.requestWithdrawal(policy, new BigDecimal("1000.00"),
            "+255700000001", "staff-one"));
        assertThat(withdrawal.withdrawalId()).isNotNull();
    }
}
