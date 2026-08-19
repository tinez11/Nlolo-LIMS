package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 9: HTTP-level contract coverage for {@code GlPostingController}/{@code ChartOfAccountController}
 * against {@code api/openapi/openapi-finaccounting.yaml}. Follows {@code ReinsuranceContractTest}'s
 * structure: {@code @Testcontainers} + {@code @AutoConfigureMockMvc} + {@code @SpringBootTest} +
 * {@code jwt()} post-processors + {@code openApi().isValid(SPEC_PATH)} <b>paired with</b>
 * {@link SpecTypeConformance#matchesDeclaredTypes} on every response carrying a decimal.
 *
 * <p>Like {@code ReinsuranceContractTest}/{@code DistributionContractTest}, this class runs against
 * the Testcontainers Postgres <b>superuser</b>, not {@code app_role} -- RLS/grant coverage is
 * {@code AppRolePrivilegesIntegrationTest}'s and {@code RowLevelSecurityIntegrationTest}'s job, not
 * this one's.
 *
 * <p><b>Fixture-seeding technique, chosen deliberately rather than guessed.</b> {@code
 * FinaccountingApi} exposes no write method at all -- every posting is derived from a domain event
 * by this module's own listeners (see that interface's javadoc) -- and the one write primitive that
 * exists, {@code FinaccountingApiImpl.postEntry(JournalEntry)}, is package-private to {@code
 * finaccounting.application} (its only intended callers are Task 6's per-source-module listeners).
 * This class lives in the bare {@code finaccounting} package (matching every other module's
 * *ContractTest*), so it cannot reach {@code postEntry} directly the way {@code
 * FinaccountingApiIntegrationTest} does from inside {@code .application}. Rather than expanding the
 * migration list to drive a real cross-module event chain (billing/claims/policy/underwriting are
 * deliberately NOT in this class's migration list below), this mirrors {@code
 * DistributionContractTest}'s own established precedent for exactly this situation -- {@code
 * commissionStatementRepository} is autowired there and used directly to seed/manipulate fixtures
 * for a table with no direct write endpoint. {@link #seedEntry} does the same with {@code
 * JournalEntryRepository}/{@code GlPostingRepository} (both public), replaying {@code postEntry}'s
 * own save order (entry first, so its {@code @GeneratedValue} id is real, then one {@code GlPosting}
 * per leg) rather than reimplementing its idempotency/balance-check semantics, which are not what
 * this class is testing.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinaccountingContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-finaccounting.yaml";
    private static final String CURRENCY = "TZS";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        // policyloan/V1 and V2 are required even though this test never touches a loan: gl_posting
        // is PARTITION BY RANGE (created_at), Postgres does not cascade RLS/GRANT/REVOKE from a
        // partitioned parent onto its own hand-written partitions, and policyloan/V2 installs the
        // one mechanism on this platform (trg_partition_controls) that mirrors those controls onto
        // every partition -- exactly FinaccountingApiIntegrationTest's own documented rationale.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private GlPostingRepository glPostingRepository;
    @Autowired private ChartOfAccountSeeder chartOfAccountSeeder;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
    }

    // --- token shapes -------------------------------------------------------------------------

    /** FINANCE_OFFICER/ADMIN is the decision every controller in this module's javadoc records --
     * there is no finaccounting-specific staff role, mirroring M7's/M8's identical decision for
     * distribution/reinsurance. */
    private static RequestPostProcessor financeStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
            .jwt(builder -> builder.subject("finance-officer").claim("tenant_id", tenantId.toString()));
    }

    /** Staff, but the WRONG fine-grained role -- proves the gate is on FINANCE_OFFICER/ADMIN and
     * not merely on being staff. */
    private static RequestPostProcessor underwriterStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
            .jwt(builder -> builder.subject("underwriter").claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor agentOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.subject("agent").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** See the class javadoc for why this seeds through the repositories directly rather than
     * through {@code FinaccountingApiImpl.postEntry}. Always a balanced two-leg entry: DR
     * {@code CASH} / CR {@code PREMIUM_RECEIVABLE} for the same amount. */
    private JournalEntry seedEntry(UUID tenantId, String sourceEvent, String sourceRef, String period,
                                    String policyNumber, String amount) {
        TenantContext.set(tenantId);
        JournalEntry entry = new JournalEntry(tenantId, sourceEvent, sourceRef, period, policyNumber, "system:test");
        entry.addLeg(PostingRule.CASH, PostingDirection.DR, new BigDecimal(amount), CURRENCY);
        entry.addLeg(PostingRule.PREMIUM_RECEIVABLE, PostingDirection.CR, new BigDecimal(amount), CURRENCY);
        journalEntryRepository.save(entry);
        for (JournalEntry.Leg leg : entry.getLegs()) {
            glPostingRepository.save(new GlPosting(tenantId, entry.getJournalEntryId(), leg.accountCode(),
                leg.direction(), leg.amount(), leg.currency(), period, policyNumber, sourceEvent, sourceRef));
        }
        TenantContext.clear();
        return entry;
    }

    // ============================================================================================
    // GET /gl-postings
    // ============================================================================================

    @Test
    void listGlPostingsReturns200AndThePeriodAndPolicyNumberFiltersGenuinelyNarrow() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedEntry(tenantId, "billing.PremiumInvoiceGenerated", "gl-ct-list-1", "2026-08", "POL-GL-A", "15000.00");

        mockMvc.perform(get("/gl-postings").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "JournalEntryView"))
            .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/gl-postings").param("period", "2026-08").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // The falsifiable half -- a filter that ignored its argument would still return the row.
        mockMvc.perform(get("/gl-postings").param("period", "2026-09").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/gl-postings").param("policyNumber", "POL-GL-A").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // The falsifiable half for policyNumber too.
        mockMvc.perform(get("/gl-postings").param("policyNumber", "POL-GL-ZZ").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listGlPostingsReturns403ForStaffWithoutTheFinanceRoleAndForAnAgentToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // A real target must exist first, so a broken @PreAuthorize would return 200 rather than an
        // incidental empty-array 200 that would pass for the wrong reason.
        seedEntry(tenantId, "billing.PremiumInvoiceGenerated", "gl-ct-403", "2026-08", "POL-GL-403", "15000.00");

        mockMvc.perform(get("/gl-postings").with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/gl-postings").with(agentOf(tenantId, UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // GET /gl-postings/{journalEntryId}
    // ============================================================================================

    @Test
    void getGlPostingReturns200WithBothLegsAndEqualDrAndCrTotals() throws Exception {
        UUID tenantId = UUID.randomUUID();
        JournalEntry entry = seedEntry(tenantId, "claims.ClaimSettled", "gl-ct-get-1", "2026-08",
            "POL-GL-GET", "25000.00");

        // GlPostingRepository orders legs by direction ASC -- 'CR' sorts before 'DR' -- so index 0
        // is the CR leg and index 1 is the DR leg, both for the same amount (this fixture's
        // invariant), which is what proves the entry's DR/CR totals are equal.
        mockMvc.perform(get("/gl-postings/{journalEntryId}", entry.getJournalEntryId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "JournalEntryView"))
            .andExpect(jsonPath("$.journalEntryId").value(entry.getJournalEntryId().toString()))
            .andExpect(jsonPath("$.postings.length()").value(2))
            .andExpect(jsonPath("$.postings[0].direction").value("CR"))
            .andExpect(jsonPath("$.postings[0].amount.amount").value("25000.00"))
            .andExpect(jsonPath("$.postings[1].direction").value("DR"))
            .andExpect(jsonPath("$.postings[1].amount.amount").value("25000.00"));
    }

    @Test
    void getGlPostingReturns404ForAnUnknownIdAndReturns404NotForbiddenForACrossTenantId() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        JournalEntry entry = seedEntry(tenantA, "billing.PremiumInvoiceGenerated", "gl-ct-404", "2026-08",
            "POL-GL-404", "5000.00");

        mockMvc.perform(get("/gl-postings/{journalEntryId}", UUID.randomUUID()).with(financeStaffOf(tenantA)))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("JOURNAL_ENTRY_NOT_FOUND"));

        // 404, never 403: a 403 would confirm to tenant B that this id exists somewhere.
        mockMvc.perform(get("/gl-postings/{journalEntryId}", entry.getJournalEntryId()).with(financeStaffOf(tenantB)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("JOURNAL_ENTRY_NOT_FOUND"));
    }

    // ============================================================================================
    // GET /chart-of-accounts
    // ============================================================================================

    @Test
    void listChartOfAccountsReturns200WithTheNineSeededAccountsAnd403ForANonFinanceRole() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");
        TenantContext.clear();

        mockMvc.perform(get("/chart-of-accounts").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(9));

        // A real chart must exist first (seeded above), so a broken @PreAuthorize would return 200
        // rather than an incidental 200-with-nothing-interesting that would pass for the wrong reason.
        mockMvc.perform(get("/chart-of-accounts").with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }
}
