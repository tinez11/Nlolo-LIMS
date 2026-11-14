package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterStateException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shareholders register (2026-11-14): an issue recorded by one person and approved by a second posts Dr bank,
 * Cr share capital, Cr share premium in the month the money came; the register and the books agree; the three checks.
 * Against the real ledger guards (app_role, RLS).
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ShareRegisterIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "share_register_it_password";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/refdata/V12__share_capital_reason_code.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
            "db-migrations/policyloan/V9__payout_approval.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
            "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
            "db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql",
            "db-migrations/finaccounting/V11__groups_and_policy_classification.sql",
            "db-migrations/finaccounting/V12__unposted_events_and_paa_earning.sql",
            "db-migrations/finaccounting/V13__disbursement_method.sql",
            "db-migrations/finaccounting/V14__manual_journals.sql",
            "db-migrations/finaccounting/V15__engine_period_cycle.sql",
            "db-migrations/finaccounting/V16__expense_allocation.sql",
            "db-migrations/finaccounting/V17__year_end_close.sql",
            "db-migrations/finaccounting/V18__policy_snapshot_lives.sql",
            "db-migrations/finaccounting/V19__acted_alone.sql",
            "db-migrations/finaccounting/V20__dismissal_needs_a_second_person.sql",
            "db-migrations/finaccounting/V21__baseline_sign_off.sql",
            "db-migrations/finaccounting/V22__share_register.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ShareRegisterApi register;
    @Autowired private FinaccountingApi ledger;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private UUID demoHoldings() {
        register.setNominal("ORDINARY", new BigDecimal("1000"), "finance-one");
        return register.addShareholder(new ShareRegisterApi.ShareholderInput("Demo Holdings Ltd", "COMPANY", true,
            "REG-001", null, null), "finance-one").shareholderId();
    }

    private static ShareRegisterApi.IssueInput issue(UUID holder, long shares, String price, LocalDate on) {
        BigDecimal p = new BigDecimal(price);
        return new ShareRegisterApi.IssueInput(holder, "ORDINARY", shares, p, p.multiply(BigDecimal.valueOf(shares)),
            on, "1110", "Board resolution 2026/01");
    }

    @Test
    void anIssueApprovedByASecondPersonPostsCapitalAndPremium() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        var proposed = register.propose(issue(holder, 1_000_000, "1500", TODAY), "finance-one");
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(proposed.shareCapital()).isEqualByComparingTo("1000000000.00");
        assertThat(proposed.sharePremium()).isEqualByComparingTo("500000000.00");
        assertThat(register.capital().ledgerCapital()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> register.approve(proposed.issueId(), "finance-one"))
            .isInstanceOf(ShareRegisterStateException.class)
            .hasMessage("You recorded this issue; a second person must approve it");

        var approved = register.approve(proposed.issueId(), "finance-approver");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.actedAlone()).isFalse();
        assertThat(jdbc.queryForObject("SELECT period FROM finaccounting.journal_entry WHERE journal_entry_id = ?",
            String.class, approved.journalEntryId())).isEqualTo(YearMonth.from(TODAY).toString());
        assertThat(jdbc.queryForList("SELECT account_code || ' ' || direction || ' ' || amount FROM finaccounting.gl_posting"
            + " WHERE journal_entry_id = ? ORDER BY account_code", String.class, approved.journalEntryId()))
            .containsExactly("1110 DR 1500000000.00", "3110 CR 1000000000.00", "3120 CR 500000000.00");

        var capital = register.capital();
        assertThat(capital.paidUpCapital()).isEqualByComparingTo("1500000000.00");
        assertThat(capital.ledgerCapital()).isEqualByComparingTo("1500000000.00");
        assertThat(capital.books().state()).isEqualTo("MET");
        assertThat(capital.tanzanian().state()).isEqualTo("MET");
        assertThat(capital.minimum().state()).isEqualTo("NOT_SET");
        assertThat(register.shareholders()).singleElement()
            .satisfies(s -> assertThat(s.shares()).isEqualTo(1_000_000));
        assertThatThrownBy(() -> register.approve(proposed.issueId(), "finance-approver"))
            .isInstanceOf(ShareRegisterStateException.class).hasMessageContaining("this one is approved");
    }

    @Test
    void theRecorderMayApproveAloneOnlyWithApproveOwn() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        var proposed = register.propose(issue(holder, 10, "1000", TODAY), "finance-alone");
        signedInAs("finance-alone", "ROLE_FINANCE_OFFICER", "ROLE_APPROVE_OWN_WORK");
        var approved = register.approve(proposed.issueId(), "finance-alone");
        assertThat(approved.actedAlone()).isTrue();
        assertThat(jdbc.queryForObject("SELECT acted_alone FROM finaccounting.journal_entry WHERE journal_entry_id = ?",
            Boolean.class, approved.journalEntryId())).isTrue();
    }

    @Test
    void aRejectionSaysWhyAndPostsNothingAndOnlyTheRecorderWithdraws() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        var proposed = register.propose(issue(holder, 10, "1000", TODAY), "finance-one");
        assertThatThrownBy(() -> register.reject(proposed.issueId(), " ", "finance-approver"))
            .isInstanceOf(FinaccountingValidationException.class);
        var rejected = register.reject(proposed.issueId(), "Wrong shareholder", "finance-approver");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.decisionReason()).isEqualTo("Wrong shareholder");
        assertThat(rejected.journalEntryId()).isNull();
        assertThat(register.capital().ledgerCapital()).isEqualByComparingTo("0");

        var second = register.propose(issue(holder, 10, "1000", TODAY), "finance-one");
        assertThatThrownBy(() -> register.withdraw(second.issueId(), "finance-two"))
            .isInstanceOf(ShareRegisterStateException.class);
        assertThat(register.withdraw(second.issueId(), "finance-one").status()).isEqualTo("WITHDRAWN");
    }

    @Test
    void aDiscountAFutureDateAndAnAutomaticBankAreRefused() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        assertThatThrownBy(() -> register.propose(issue(holder, 10, "900", TODAY), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("below their nominal value");
        assertThatThrownBy(() -> register.propose(issue(holder, 10, "1000", TODAY.plusDays(1)), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("future");
        var autoBank = new ShareRegisterApi.IssueInput(holder, "ORDINARY", 10, new BigDecimal("1000"),
            new BigDecimal("10000"), TODAY, "1120", "Board resolution 2026/01");
        assertThatThrownBy(() -> register.propose(autoBank, "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("1120");
        assertThat(register.bankAccounts()).extracting(ShareRegisterApi.BankAccountView::code).contains("1110")
            .doesNotContain("1120", "1130", "1140");
    }

    @Test
    void theNominalValueIsFixedOnceSharesAreIssuedAndTheMinimumKeepsItsHistory() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        register.approve(register.propose(issue(holder, 10, "1000", TODAY), "finance-one").issueId(), "finance-approver");
        assertThatThrownBy(() -> register.setNominal("ORDINARY", new BigDecimal("500"), "finance-one"))
            .isInstanceOf(ShareRegisterStateException.class).hasMessageContaining("already issued");

        register.setMinimum(new BigDecimal("1000000000"), "finance-one");
        var c = register.capital();
        assertThat(c.minimum().state()).isEqualTo("NOT_MET");
        assertThat(c.minimumPaidUp()).isEqualByComparingTo("1000000000");
        register.setMinimum(null, "finance-one");
        assertThat(register.capital().minimum().state()).isEqualTo("NOT_SET");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finaccounting.capital_minimum", Integer.class)).isEqualTo(2);
    }

    @Test
    void anIssueIntoALockedMonthIsRefusedAtApproval() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        LocalDate lastMonth = TODAY.minusMonths(1).withDayOfMonth(1);
        var proposed = register.propose(issue(holder, 10, "1000", lastMonth), "finance-one");
        String period = YearMonth.from(lastMonth).toString();
        ledger.startClosing(period, "finance-one");
        ledger.lockPeriod(period, "finance-one");
        assertThatThrownBy(() -> register.approve(proposed.issueId(), "finance-approver"))
            .isInstanceOf(ShareRegisterStateException.class).hasMessage("That month is locked");
    }

    /** A staff sign-in holding these roles, as a request would carry it. */
    private static void signedInAs(String subject, String... roles) {
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_REALM_STAFF"));
        for (String r : roles) {
            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(r));
        }
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t")
            .header("alg", "none").subject(subject).claim("tenant_id", TenantContext.get().toString()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, authorities));
    }
}
