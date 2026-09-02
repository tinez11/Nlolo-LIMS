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
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * <p><b>Fixture-seeding technique for JOURNAL ENTRIES, chosen deliberately rather than guessed.</b>
 * Journal entries/GL postings still have no write endpoint at all -- every posting is derived from
 * a domain event by this module's own listeners (see {@code FinaccountingApi}'s javadoc) -- and the
 * one write primitive that exists, {@code FinaccountingApiImpl.postEntry(JournalEntry)}, is
 * package-private to {@code finaccounting.application} (its only intended callers are Task 6's
 * per-source-module listeners). This class lives in the bare {@code finaccounting} package
 * (matching every other module's *ContractTest*), so it cannot reach {@code postEntry} directly the
 * way {@code FinaccountingApiIntegrationTest} does from inside {@code .application}. Rather than
 * expanding the migration list to drive a real cross-module event chain (billing/claims/policy/
 * underwriting are deliberately NOT in this class's migration list below), this mirrors {@code
 * DistributionContractTest}'s own established precedent for exactly this situation -- {@code
 * commissionStatementRepository} is autowired there and used directly to seed/manipulate fixtures
 * for a table with no direct write endpoint. {@link #seedEntry} does the same with {@code
 * JournalEntryRepository}/{@code GlPostingRepository} (both public), replaying {@code postEntry}'s
 * own save order (entry first, so its {@code @GeneratedValue} id is real, then one {@code GlPosting}
 * per leg) rather than reimplementing its idempotency/balance-check semantics, which are not what
 * this class is testing.
 *
 * <p><b>The chart of accounts is NOT read-only</b> (added after M9 shipped) -- its own
 * create/rename/delete endpoints are exercised directly below through real HTTP calls, unlike
 * journal entries/GL postings above.
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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql");
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
        // Required since finaccounting/V3: gl_posting.account_code is a real foreign key into
        // chart_of_account (tenant_id, account_code), so a tenant with no chart has no postable
        // account at all. Production seeds this in every listener before posting; this helper writes
        // through the repositories directly, below that layer, so it seeds it here.
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");
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

        // The response is a paged envelope -- {items, page} -- not a bare array, since finding I3.
        // SpecTypeConformance is pointed at the envelope schema so its walk covers the page meta as
        // well as each item's money fields; it recurses into items itself.
        mockMvc.perform(get("/gl-postings").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "JournalEntrySearchResponse"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.page.page").value(0))
            .andExpect(jsonPath("$.page.pageSize").value(20))
            .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(get("/gl-postings").param("period", "2026-08").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));

        // The falsifiable half -- a filter that ignored its argument would still return the row.
        mockMvc.perform(get("/gl-postings").param("period", "2026-09").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(get("/gl-postings").param("policyNumber", "POL-GL-A").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));

        // The falsifiable half for policyNumber too.
        mockMvc.perform(get("/gl-postings").param("policyNumber", "POL-GL-ZZ").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));

        // Both filters at once, over the wire -- the combined DB-side finder (finding M8).
        mockMvc.perform(get("/gl-postings").param("period", "2026-08").param("policyNumber", "POL-GL-A")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/gl-postings").param("period", "2026-09").param("policyNumber", "POL-GL-A")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));

        // pageSize is capped server-side at 100 no matter what a client asks for (finding I3): an
        // uncapped pageSize is just the unbounded read again, spelled as a query parameter.
        mockMvc.perform(get("/gl-postings").param("pageSize", "5000").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page.pageSize").value(100));
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

    // ============================================================================================
    // POST /chart-of-accounts
    // ============================================================================================

    @Test
    void createAccountReturns201AndDerivesAccountTypeAndNormalBalanceFromTheCode() throws Exception {
        UUID tenantId = UUID.randomUUID();

        // 3xxx is EQUITY/CR by the five-block convention -- none of the nine seeded accounts use
        // this block, so this is genuinely a new account, not a seed collision.
        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"3000","name":"Retained Earnings"}"""))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ChartOfAccountView"))
            .andExpect(jsonPath("$.accountCode").value("3000"))
            .andExpect(jsonPath("$.name").value("Retained Earnings"))
            .andExpect(jsonPath("$.accountType").value("EQUITY"))
            .andExpect(jsonPath("$.normalBalance").value("CR"));

        mockMvc.perform(get("/chart-of-accounts").with(financeStaffOf(tenantId)))
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createAccountReturns409ForADuplicateAccountCode() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String body = """
            {"accountCode":"3100","name":"Share Capital"}""";

        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ACCOUNT_CODE"));
    }

    @Test
    void createAccountReturns400ForAMalformedAccountCodeOrABlankName() throws Exception {
        UUID tenantId = UUID.randomUUID();

        // "9000" has no valid block (only 1-5 are defined). Deliberately NOT paired with the
        // OpenApi request/response matcher here: this request is, by design, itself invalid
        // against the spec's own declared `accountCode` pattern, so the validator's own
        // request-side check would throw before the response could ever be asserted on --
        // exactly what a test proving the SERVER's 400 needs to send, so only the status is
        // checked for this one case.
        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"9000","name":"Bogus Block"}"""))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"3200","name":""}"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createAccountReturns403ForANonFinanceRole() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/chart-of-accounts").with(underwriterStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"3300","name":"Should Never Be Created"}"""))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // PUT /chart-of-accounts/{accountCode}
    // ============================================================================================

    @Test
    void renameAccountReturns200AndUpdatesTheNameOnly() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"3400","name":"Original Name"}"""))
            .andExpect(status().isCreated());

        mockMvc.perform(put("/chart-of-accounts/{accountCode}", "3400").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Renamed"}"""))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.accountCode").value("3400"))
            .andExpect(jsonPath("$.name").value("Renamed"))
            // Untouched by the rename -- both stay derived from the code.
            .andExpect(jsonPath("$.accountType").value("EQUITY"))
            .andExpect(jsonPath("$.normalBalance").value("CR"));
    }

    @Test
    void renameAccountReturns404ForAnUnknownAccountCode() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(put("/chart-of-accounts/{accountCode}", "3500").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Does Not Exist"}"""))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    // ============================================================================================
    // DELETE /chart-of-accounts/{accountCode}
    // ============================================================================================

    @Test
    void deleteAccountReturns204WhenNoPostingReferencesIt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"3600","name":"Never Posted To"}"""))
            .andExpect(status().isCreated());

        mockMvc.perform(delete("/chart-of-accounts/{accountCode}", "3600").with(financeStaffOf(tenantId)))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/chart-of-accounts").with(financeStaffOf(tenantId)))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteAccountReturns409WhenARealPostingReferencesIt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // seedEntry posts a real DR CASH ("1000") / CR PREMIUM_RECEIVABLE ("1200") leg pair.
        seedEntry(tenantId, "billing.PremiumInvoiceGenerated", "gl-ct-inuse", "2026-08", "POL-GL-INUSE", "1000.00");

        mockMvc.perform(delete("/chart-of-accounts/{accountCode}", PostingRule.CASH).with(financeStaffOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_IN_USE"));

        // Still there -- the rejected delete must not have removed it.
        mockMvc.perform(get("/chart-of-accounts").with(financeStaffOf(tenantId)))
            .andExpect(jsonPath("$.length()").value(9));
    }

    @Test
    void deleteAccountReturns404ForAnUnknownAccountCode() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(delete("/chart-of-accounts/{accountCode}", "3700").with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }
}
