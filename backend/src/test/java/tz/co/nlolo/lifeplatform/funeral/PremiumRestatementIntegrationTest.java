package tz.co.nlolo.lifeplatform.funeral;

import org.junit.jupiter.api.BeforeAll;
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
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * Billing's side of a premium restatement (policy.PremiumRestated), on an ordinary monthly policy so nothing
 * but billing is exercised: untouched instalments from the effective date restated in place, a paid one left
 * alone, the schedule replaced rather than mutated.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class PremiumRestatementIntegrationTest {

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
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private FuneralTestFixtures fixtures;
    @Autowired private AnnuityTestFixtures annuityFixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private JdbcTemplate jdbc;

    private String monthlyPolicyAt(String premium) {
        var ordinary = annuityFixtures.publishOrdinary(TENANT);
        UUID someone = fixtures.person(TENANT, 40, Sex.MALE);
        return asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(someone,
            ordinary.productId(), ordinary.versionId(), new BigDecimal("1000000"), "TZS", new BigDecimal(premium), "TZS",
            "MONTHLY", null, List.of(), "billing restatement", null, null, null, null, IssuanceBasis.MIGRATION),
            "test-staff").policyNumber());
    }

    private List<InvoiceView> invoices(String policyNumber) {
        return asTenant(TENANT, () -> billingApi.listInvoices(policyNumber, null)).stream()
            .sorted(Comparator.comparing(InvoiceView::dueDate)).toList();
    }

    private void restate(String policyNumber, String amount, LocalDate from) {
        annuityFixtures.publish(TENANT, "policy.PremiumRestated", Map.of("policyNumber", policyNumber,
            "premiumAmount", Map.of("amount", amount, "currencyCode", "TZS"), "effectiveFrom", from.toString(),
            "reason", "test"));
    }

    @Test
    void untouchedInstalmentsFromTheDateAreRestatedAndEarlierOnesAreNot() {
        String policyNumber = monthlyPolicyAt("1000.00");
        List<InvoiceView> before = invoices(policyNumber);
        LocalDate third = before.get(2).dueDate();

        restate(policyNumber, "1500.00", third);

        List<InvoiceView> after = invoices(policyNumber);
        assertThat(after).hasSameSizeAs(before);
        assertThat(after).allSatisfy(i -> assertThat(i.amount())
            .isEqualByComparingTo(i.dueDate().isBefore(third) ? "1000.00" : "1500.00"));
        // The same invoices, restated in place -- not waived and re-raised.
        assertThat(after).extracting(InvoiceView::invoiceId).containsExactlyElementsOf(
            before.stream().map(InvoiceView::invoiceId).toList());
        assertThat(after).allMatch(i -> i.status().name().equals("DUE"));
    }

    @Test
    void anInstalmentPaidInAdvanceKeepsItsAmount() {
        String policyNumber = monthlyPolicyAt("1000.00");
        InvoiceView second = invoices(policyNumber).get(1);
        asTenant(TENANT, () -> billingApi.applyConfirmedPayment(second.invoiceId(), new BigDecimal("1000.00"), "TZS", "PREPAID-1"));

        restate(policyNumber, "1500.00", invoices(policyNumber).get(0).dueDate());

        List<InvoiceView> after = invoices(policyNumber);
        assertThat(after.get(0).amount()).isEqualByComparingTo("1500.00");
        assertThat(after.get(1).amount()).isEqualByComparingTo("1000.00");
        assertThat(after.get(2).amount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void theScheduleIsReplacedNotMutated() {
        String policyNumber = monthlyPolicyAt("1000.00");
        restate(policyNumber, "1500.00", invoices(policyNumber).get(0).dueDate());

        List<Map<String, Object>> schedules = jdbc.queryForList("SELECT status, premium_amount FROM billing.billing_schedule"
            + " WHERE tenant_id = ? AND policy_number = ? ORDER BY created_at", TENANT, policyNumber);
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).get("status")).isEqualTo("TERMINATED");
        assertThat((BigDecimal) schedules.get(0).get("premium_amount")).isEqualByComparingTo("1000.00");
        assertThat(schedules.get(1).get("status")).isEqualTo("ACTIVE");
        assertThat((BigDecimal) schedules.get(1).get("premium_amount")).isEqualByComparingTo("1500.00");
    }

    @Test
    void aScheduleThatIsNotActiveIsLeftAlone() {
        String policyNumber = monthlyPolicyAt("1000.00");
        jdbc.update("UPDATE billing.billing_schedule SET status = 'SUSPENDED' WHERE tenant_id = ? AND policy_number = ?",
            TENANT, policyNumber);

        restate(policyNumber, "1500.00", invoices(policyNumber).get(0).dueDate());

        assertThat(invoices(policyNumber)).allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo("1000.00"));
    }
}
