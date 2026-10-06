package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
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
 * create/update/activate/deactivate/delete endpoints are exercised directly below through real
 * HTTP calls, unlike journal entries/GL postings above. Since finaccounting/V5 the chart is also
 * a HIERARCHY, so the tests below cover the rules that come with one: a child's code must sit
 * inside its parent's block, creating a child turns its parent into a header, and an account with
 * children cannot be deleted.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FinaccountingContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-finaccounting.yaml";
    private static final String CURRENCY = "TZS";

    /** The full seeded chart -- see ChartOfAccountBlueprint. Not a literal 36, so this stops
     *  being a number two files have to agree on by hand. */
    private static final int EXPECTED_CHART_SIZE = ChartOfAccountBlueprint.accounts().size();

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
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
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
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
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
            "db-migrations/finaccounting/V15__engine_period_cycle.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private GlPostingRepository glPostingRepository;
    @Autowired private ChartOfAccountSeeder chartOfAccountSeeder;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
    }

    // --- token shapes -------------------------------------------------------------------------

    /** FINANCE_OFFICER/ADMIN is the decision every controller in this module's javadoc records --
     * there is no finaccounting-specific staff role, mirroring M7's/M8's identical decision for
     * distribution/reinsurance. */
    private static RequestPostProcessor financeStaffOf(UUID tenantId) {
        return financeStaffOf(tenantId, "finance-officer");
    }

    /** A named finance officer: the period and register controls need a second person. */
    private static RequestPostProcessor financeStaffOf(UUID tenantId, String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
            .jwt(builder -> builder.subject(subject).claim("tenant_id", tenantId.toString()));
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
        entry.addLeg("1140", PostingDirection.DR, new BigDecimal(amount), CURRENCY);
        entry.addLeg("2122", PostingDirection.CR, new BigDecimal(amount), CURRENCY);
        // One transaction, as finaccounting V10 requires: a journal balances at commit, and its lines may only be
        // written by the transaction that wrote it.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            journalEntryRepository.save(entry);
            for (JournalEntry.Leg leg : entry.getLegs()) {
                glPostingRepository.save(new GlPosting(tenantId, entry.getJournalEntryId(), leg.accountCode(),
                    leg.direction(), leg.amount(), leg.currency(), period, policyNumber, sourceEvent, sourceRef,
                    leg.dimensions()));
            }
        });
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
    void listChartOfAccountsReturns200WithTheSeededChartAnd403ForANonFinanceRole() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");
        TenantContext.clear();

        mockMvc.perform(get("/chart-of-accounts").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(EXPECTED_CHART_SIZE));

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

        // "0900" is in no class (the posting guide defines 1-9). Deliberately NOT paired with the
        // OpenApi request/response matcher here: this request is, by design, itself invalid
        // against the spec's own declared `accountCode` pattern, so the validator's own
        // request-side check would throw before the response could ever be asserted on --
        // exactly what a test proving the SERVER's 400 needs to send, so only the status is
        // checked for this one case.
        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"0900","name":"Bogus Block"}"""))
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
    void updateAccountReturns200AndLeavesTheDerivedFieldsUntouched() throws Exception {
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
            // Untouched by the update -- both stay derived from the code, and so do
            .andExpect(jsonPath("$.accountType").value("EQUITY"))
            .andExpect(jsonPath("$.normalBalance").value("CR"));
    }

    @Test
    void updateAccountReturns404ForAnUnknownAccountCode() throws Exception {
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
        // seedEntry posts a real DR CASH ("1140") / CR PREMIUM_RECEIVABLE ("2122") leg pair.
        seedEntry(tenantId, "billing.PremiumInvoiceGenerated", "gl-ct-inuse", "2026-08", "POL-GL-INUSE", "1000.00");

        mockMvc.perform(delete("/chart-of-accounts/{accountCode}", "1140").with(financeStaffOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_IN_USE"));

        // Still there -- the rejected delete must not have removed it.
        mockMvc.perform(get("/chart-of-accounts").with(financeStaffOf(tenantId)))
            .andExpect(jsonPath("$.length()").value(EXPECTED_CHART_SIZE));
    }

    @Test
    void deleteAccountReturns404ForAnUnknownAccountCode() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(delete("/chart-of-accounts/{accountCode}", "3700").with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    // ============================================================================================
    // The hierarchy (finaccounting/V5)
    // ============================================================================================

    private void seedChart(UUID tenantId) {
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");
        TenantContext.clear();
    }

    @Test
    void createsAChildAccountUnderAnExistingParent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"1295","parentCode":"1200","name":"Sundry investments"}"""))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.parentCode").value("1200"))
            // 1200 Financial investments is level 2, so its child is level 3.
            .andExpect(jsonPath("$.level").value(3))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.currency").value("TZS"))
            .andExpect(jsonPath("$.postingAllowed").value(true))
            // Derived from the leading digit, never sent by the client.
            .andExpect(jsonPath("$.accountType").value("ASSET"))
            .andExpect(jsonPath("$.normalBalance").value("DR"));
    }

    /** The prefix rule: a child's code must begin with its parent's code minus trailing zeros.
     *  422, not 400 -- the body is well formed, it is the domain rule that rejects it. */
    @Test
    void rejectsAChildWhoseCodeFallsOutsideItsParentsBlock() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"2160","parentCode":"1200","name":"Wrong block"}"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FINACCOUNTING_VALIDATION_FAILED"));
    }

    @Test
    void rejectsAChildOfAnAccountThatAlreadyCarriesPostings() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        // 1140 Mobile money wallets is a real posting target (CASH), so it can never become a header.
        seedEntry(tenantId, "billing.PremiumInvoiceGenerated", "gl-ct-parent", "2026-08",
            "POL-GL-PARENT", "1000.00");

        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"1141","parentCode":"1140","name":"Under a posted-to account"}"""))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_IN_USE"));
    }

    /** Creating a child turns its parent into a header in the same transaction -- otherwise the
     *  invariant "an account with children never posts" would be briefly observable as false. */
    @Test
    void creatingAChildMakesItsParentAHeader() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        // 1150 Petty cash seeds as a postable leaf with no children.
        mockMvc.perform(post("/chart-of-accounts").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"1151","parentCode":"1150","name":"Branch petty cash"}"""))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/chart-of-accounts").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.accountCode == '1150')].postingAllowed").value(false));
    }

    @Test
    void deactivatesAndReactivatesAnAccount() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        mockMvc.perform(post("/chart-of-accounts/{accountCode}/deactivate", "1150")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(post("/chart-of-accounts/{accountCode}/activate", "1150")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void deactivateReturns404ForAnUnknownAccountAndIsGatedOnFinanceOrAdmin() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        mockMvc.perform(post("/chart-of-accounts/{accountCode}/deactivate", "3700")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(post("/chart-of-accounts/{accountCode}/deactivate", "1150")
                .with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void refusesToDeleteAnAccountWithChildren() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        mockMvc.perform(delete("/chart-of-accounts/{accountCode}", "1200").with(financeStaffOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_HAS_CHILDREN"));
    }

    @Test
    void updatesNameAndDescriptionTogether() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        mockMvc.perform(put("/chart-of-accounts/{accountCode}", "1150").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Investments and securities","description":"Long-term holdings"}"""))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.name").value("Investments and securities"))
            .andExpect(jsonPath("$.description").value("Long-term holdings"));
    }

    // ============================================================================================
    // Accounting periods and the accounting policy register (IFRS 17 I1)
    // ============================================================================================

    @Test
    void aPeriodClosesLocksAndReopensThroughASecondPersonOnTheWire() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String period = "2024-01";

        mockMvc.perform(get("/finance/periods/{period}", period).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/finance/periods/{period}/closing", period).with(financeStaffOf(tenantId, "alice")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("CLOSING"));
        mockMvc.perform(post("/finance/periods/{period}/lock", period).with(financeStaffOf(tenantId, "alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("LOCKED"))
            .andExpect(jsonPath("$.lockedBy").value("alice"));
        mockMvc.perform(post("/finance/periods/{period}/reopen-request", period).with(financeStaffOf(tenantId, "alice"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"Late bank statement"}"""))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.reopenRequestedBy").value("alice"));
        mockMvc.perform(post("/finance/periods/{period}/reopen-approval", period).with(financeStaffOf(tenantId, "alice")))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("PERIOD_STATE"))
            .andExpect(jsonPath("$.detail").value("A second person approves reopening a period"));
        mockMvc.perform(post("/finance/periods/{period}/reopen-approval", period).with(financeStaffOf(tenantId, "bob")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.reopenedBy").value("bob"));
        mockMvc.perform(get("/finance/periods").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].period").value(period));

        mockMvc.perform(get("/finance/periods").with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    /** IFRS 17 I2: a contract's classification on the wire; finance only. */
    @Test
    void aPolicyClassificationIsReadOverTheWireByFinanceOnly() throws Exception {
        UUID tenantId = UUID.randomUUID();
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("policyNumber", "POL-CT-CLS");
        payload.put("issueDate", "2026-03-01");
        payload.put("portfolioCode", "TERM");
        payload.put("cohortYear", 2026);
        payload.put("profitabilityBucket", "REMAINING");
        payload.put("salesChannel", "DIRECT");
        payload.put("branchCode", "DSM");
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
            eventPublisher.publishEvent(tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("policy.PolicyIssued", tenantId, payload)));

        mockMvc.perform(get("/finance/policy-classifications/{policyNumber}", "POL-CT-CLS").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].groupKey").value("TERM-GMM-2026-REM"))
            .andExpect(jsonPath("$[0].modelBasis").value("REGISTER"));
        mockMvc.perform(get("/finance/policy-classifications/{policyNumber}", "POL-CT-CLS").with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void anElectionIsProposedAndApprovedByASecondPersonOnTheWire() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toString();

        mockMvc.perform(get("/finance/accounting-policies").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[?(@.key == 'OCI_OPTION')].value").value(org.hamcrest.Matchers.contains("OFF")));

        String body = mockMvc.perform(post("/finance/accounting-policies").with(financeStaffOf(tenantId, "alice"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"key":"OCI_OPTION","scope":"*","value":"ON","effectiveFrom":"%s","rationale":"Match FVOCI assets"}"""
                    .formatted(today)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("PROPOSED"))
            .andReturn().getResponse().getContentAsString();
        String electionId = com.jayway.jsonpath.JsonPath.read(body, "$.electionId");

        mockMvc.perform(post("/finance/accounting-policies/{id}/approval", electionId).with(financeStaffOf(tenantId, "alice"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"signOffRef":"AC-14"}"""))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("POLICY_REGISTER_STATE"));
        mockMvc.perform(post("/finance/accounting-policies/{id}/approval", electionId).with(financeStaffOf(tenantId, "bob"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"signOffRef":"AC-14"}"""))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.registerVersion").value(
                tz.co.nlolo.lifeplatform.finaccounting.domain.PolicyRegisterBaseline.ROWS.size() + 1));

        mockMvc.perform(post("/finance/accounting-policies").with(financeStaffOf(tenantId, "alice"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"key":"OCI_OPTION","scope":"*","value":"MAYBE","effectiveFrom":"%s"}""".formatted(today)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("MAYBE is not a permitted value for OCI_OPTION"));
        mockMvc.perform(post("/finance/accounting-policies/{id}/rejection", UUID.randomUUID())
                .with(financeStaffOf(tenantId, "bob"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"No such thing"}"""))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("POLICY_ELECTION_NOT_FOUND"));
    }

    // ============================================================================================
    // IFRS 17 I3a: the posting rules and the unposted-event queue
    // ============================================================================================

    @Test
    void thePostingRulesAreReadableByFinanceOnly() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/finance/posting-rules").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.versionLabel").value("posting-rules v4"))
            .andExpect(jsonPath("$.rules[?(@.id == 'I-01')].lines[0].account").value("2142"));
        mockMvc.perform(get("/finance/posting-rules").with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void aQueuedEventIsListedDismissedWithAReasonAndThenRefusesARetry() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // An invoice for a policy nobody classified: the rules cannot choose a model, so it is queued.
        java.util.Map<String, Object> invoice = java.util.Map.of("invoiceId", UUID.randomUUID(),
            "policyNumber", "POL-UNCLASSIFIED", "dueDate", "2026-10-01",
            "amount", java.util.Map.of("amount", "450.00", "currencyCode", CURRENCY));
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
            eventPublisher.publishEvent(tz.co.nlolo.lifeplatform.DomainEventEnvelope.of(
                "billing.PremiumInvoiceGenerated", tenantId, invoice)));

        String body = mockMvc.perform(get("/finance/unposted-events").param("openOnly", "true")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].reason").value("UNMAPPED"))
            .andExpect(jsonPath("$[0].policyNumber").value("POL-UNCLASSIFIED"))
            .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$[0].id");

        mockMvc.perform(post("/finance/unposted-events/{id}/dismissal", id).with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"  "}"""))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("FINACCOUNTING_VALIDATION_FAILED"));
        mockMvc.perform(post("/finance/unposted-events/{id}/dismissal", id).with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"Test policy, never issued"}"""))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.resolution").value("DISMISSED"))
            .andExpect(jsonPath("$.resolvedBy").value("finance-officer"));

        mockMvc.perform(post("/finance/unposted-events/{id}/retry", id).with(financeStaffOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("UNPOSTED_EVENT_RESOLVED"));
        mockMvc.perform(post("/finance/unposted-events/{id}/retry", UUID.randomUUID()).with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("UNPOSTED_EVENT_NOT_FOUND"));
        mockMvc.perform(post("/finance/unposted-events/{id}/retry", id).with(financeStaffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/finance/unposted-events").with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    @Autowired private tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi manualJournals;

    /** The document store is MinIO, which this class does not start: storage is not what it tests. */
    @org.springframework.boot.test.mock.mockito.MockBean
    private tz.co.nlolo.lifeplatform.document.api.DocumentApi documentApi;

    /**
     * IFRS 17 I5a over HTTP, against the spec: an extract of an OPEN period is refused (409 ENGINE_STATE); the template
     * downloads; a file that is not the template is kept REJECTED with its errors (201); only a FINANCE_APPROVER
     * decides a run, and a rejected run cannot be approved.
     */
    @Test
    void theEngineCycleOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        org.mockito.Mockito.when(documentApi.upload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("doc-engine");

        mockMvc.perform(post("/ifrs17/periods/{period}/extracts", "2026-08").with(financeStaffOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("ENGINE_STATE"));

        mockMvc.perform(get("/ifrs17/results-template").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("ifrs17-engine-results-template.xlsx")));

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/ifrs17/engine-runs")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "results.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not a workbook".getBytes()))
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("not an Excel workbook")))
            .andReturn().getResponse().getContentAsString();
        String runId = com.jayway.jsonpath.JsonPath.read(body, "$.runId");

        var approval = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/ifrs17/engine-runs/{id}/approval", runId)
            .file(new org.springframework.mock.web.MockMultipartFile("report", "report.pdf", "application/pdf", "%PDF".getBytes()))
            .param("signOffReference", "AS-2026-08");
        mockMvc.perform(approval.with(financeStaffOf(tenantId, "finance-two")))
            .andExpect(status().isForbidden());
        mockMvc.perform(approval.with(financeApproverOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("ENGINE_STATE"));

        mockMvc.perform(get("/ifrs17/engine-runs").param("status", "REJECTED").with(financeApproverOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/ifrs17/engine-runs/{id}", runId).with(financeStaffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ENGINE_NOT_FOUND"));
    }

    /** FINANCE_APPROVER on top of FINANCE_OFFICER: the finance manager who approves manual journals (IFRS 17 I4). */
    private static RequestPostProcessor financeApproverOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_APPROVER"))
            .jwt(builder -> builder.subject("finance-approver").claim("tenant_id", tenantId.toString()));
    }

    /**
     * IFRS 17 I4: only a FINANCE_APPROVER approves a manual journal -- a second finance officer, however senior, is
     * refused at the endpoint -- and what comes back is what the spec says, the auto-reversal state included.
     */
    @Test
    void onlyAFinanceApproverApprovesAManualJournal() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        var draft = manualJournals.create(new tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput(null, CURRENCY,
            "October payroll", "Payroll summary from HR", null, "O-01", null, java.util.List.of(
                new tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput.Line("8110", PostingDirection.DR,
                    new BigDecimal("4500000.00"), "Salaries", null, null, null, null),
                new tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput.Line("2640", PostingDirection.CR,
                    new BigDecimal("4500000.00"), "NSSF and PAYE", null, null, null, null))), "finance-officer");
        manualJournals.attachDocument(draft.id(), "doc-1", "finance-officer");
        manualJournals.submit(draft.id(), "finance-officer");
        TenantContext.clear();

        mockMvc.perform(post("/finance/manual-journals/{id}/approval", draft.id()).with(financeStaffOf(tenantId, "finance-two")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/finance/manual-journals/{id}/rejection", draft.id()).with(financeStaffOf(tenantId, "finance-two"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/finance/manual-journals/{id}/approval", draft.id()).with(financeApproverOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.decidedBy").value("finance-approver"));

        mockMvc.perform(get("/finance/manual-journals").param("preparer", "finance-officer").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/finance/manual-journals").param("preparer", "someone-else").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/finance/manual-journals").with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }
}
