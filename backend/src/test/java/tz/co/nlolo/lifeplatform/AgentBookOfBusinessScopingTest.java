package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * HTTP-level coverage for the agents-realm "browse my book of business" scoping added to
 * {@code PolicyController}/{@code ClaimController} -- the gap both controllers' own comments used
 * to name as deferred ("no agent/agency data model... to resolve 'is this caller's agent identity
 * the agentOfRecord' against"), closed via {@code DistributionApi.resolveAgentTeam} once
 * {@code policy}/{@code claims} could reach it.
 *
 * <p>Lives here (not inside {@code policy} or {@code claims}' own test packages) because it is
 * genuinely cross-module: a real 2-level agent hierarchy (distribution), two policies attributed
 * to different agents (policy), and a claim filed against each (claims) -- the same shape
 * {@code RowLevelSecurityIntegrationTest} uses for its own cross-module concern.
 *
 * <p><b>Every negative test seeds a REAL, valid resource outside the caller's team first</b> (not
 * a nonexistent id), so a broken {@code @PreAuthorize}/ownership check would return 200, not an
 * incidental 404 that would pass for the wrong reason -- same discipline
 * {@code DistributionContractTest}'s own header documents.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AgentBookOfBusinessScopingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
    }

    @BeforeAll
    static void applyMigrationsAndCreateBuckets() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
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
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V7__journal_support_document_type.sql",
            "db-migrations/document/V8__reinsurance_statement_document_type.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/distribution/V5__agent_channel_and_home_branch.sql",
            "db-migrations/distribution/V6__commission_withholding.sql");

        MinioClient minioClient = MinioClient.builder()
            .endpoint(MINIO.getS3URL()).credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("documents").build());
        // MinioDocumentStorage routes DocumentType.CLAIM_EVIDENCE to "claim-evidence" specifically,
        // not the generic "documents" bucket above -- needed for the evidence-download scoping
        // tests below.
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("claim-evidence").build());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private DistributionApi distributionApi;
    @Autowired private tz.co.nlolo.lifeplatform.document.api.DocumentApi documentApi;

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildProductFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Book Scoping Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25571700" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-fixture");
        ProductSummaryView product = productApi.createProduct(productCode, "Book Scoping Product " + productCode,
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        TenantContext.clear();
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private PartyView verifiedAgentParty(UUID tenantId, String tag) {
        TenantContext.set(tenantId);
        PartyView party = partyApi.registerIndividual("Book Scoping Agent " + tag, LocalDate.of(1980, 1, 1),
            "+25571800" + String.format("%04d", Math.abs(tag.hashCode() % 10000)), null, "test-fixture");
        partyApi.submitKycEvidence(party.partyId(), KycStatus.VERIFIED, "doc-" + tag, "kyc-officer");
        TenantContext.clear();
        return party;
    }

    private String issuePolicy(UUID tenantId, Fixture fixture, UUID agentOfRecordId) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS",
            "MONTHLY", agentOfRecordId, List.of(), "Agent book scoping test fixture");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);
        TenantContext.clear();
        return policyNumber;
    }

    private UUID registerDeathClaim(UUID tenantId, UUID claimantId, String policyNumber) {
        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        ClaimView view = claimsApi.registerClaim(
            new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimantId, ClaimType.DEATH, dateOfEvent,
                new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test")),
            "book-scope-reg-" + UUID.randomUUID(), "claims-staff-fixture");
        TenantContext.clear();
        return view.claimId();
    }

    private String attachEvidence(UUID tenantId, UUID claimId) {
        TenantContext.set(tenantId);
        byte[] content = "book-scoping-evidence-bytes".getBytes();
        String documentRef = documentApi.upload("claim:" + claimId,
            tz.co.nlolo.lifeplatform.document.api.DocumentType.CLAIM_EVIDENCE, "test-fixture",
            new java.io.ByteArrayInputStream(content), content.length, "application/pdf", "evidence.pdf");
        claimsApi.attachEvidence(claimId, documentRef, "book scoping fixture evidence", "test-fixture", null);
        TenantContext.clear();
        return documentRef;
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor agentOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.subject("agent").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor staffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.subject("staff").claim("tenant_id", tenantId.toString()));
    }

    /** One tenant, a real 2-level hierarchy (supervisor -> subordinate), an unrelated top-level
     *  agent, a policy attributed to each of the two non-supervisor agents, a policy sold direct
     *  (no agent at all), and one claim against each policy. Built once per test class instance
     *  via JUnit's per-method lifecycle... actually built fresh per test that needs it, since
     *  TenantContext is cleared between tests and a shared tenant would let tests interfere. */
    private record Book(UUID tenantId, AgentView supervisor, AgentView subordinate, AgentView outsider,
                         String policyInTeam, String policyOutsideTeam, String policyDirectSold,
                         UUID claimInTeam, UUID claimOutsideTeam) {}

    private Book buildBook(String tag) {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildProductFixture(tenantId, "BOOK-" + tag);

        PartyView supervisorParty = verifiedAgentParty(tenantId, "SUP-" + tag);
        TenantContext.set(tenantId);
        AgentView supervisor = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            supervisorParty.partyId(), "LIC-SUP-" + tag, LocalDate.now().plusYears(1), null), "staff-1");
        TenantContext.clear();

        PartyView subordinateParty = verifiedAgentParty(tenantId, "SUB-" + tag);
        TenantContext.set(tenantId);
        AgentView subordinate = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            subordinateParty.partyId(), "LIC-SUB-" + tag, LocalDate.now().plusYears(1), supervisor.agentId()), "staff-1");
        TenantContext.clear();

        PartyView outsiderParty = verifiedAgentParty(tenantId, "OUT-" + tag);
        TenantContext.set(tenantId);
        AgentView outsider = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            outsiderParty.partyId(), "LIC-OUT-" + tag, LocalDate.now().plusYears(1), null), "staff-1");
        TenantContext.clear();

        // Attributed to the SUBORDINATE, not the supervisor directly -- proves the hierarchy walk
        // itself, not just a self-match.
        String policyInTeam = issuePolicy(tenantId, fixture, subordinate.agentId());
        String policyOutsideTeam = issuePolicy(tenantId, fixture, outsider.agentId());
        String policyDirectSold = issuePolicy(tenantId, fixture, null);

        UUID claimInTeam = registerDeathClaim(tenantId, fixture.applicantId(), policyInTeam);
        UUID claimOutsideTeam = registerDeathClaim(tenantId, fixture.applicantId(), policyOutsideTeam);

        return new Book(tenantId, supervisor, subordinate, outsider,
            policyInTeam, policyOutsideTeam, policyDirectSold, claimInTeam, claimOutsideTeam);
    }

    // ============================================================================================
    // GET /policies -- search scoping
    // ============================================================================================

    @Test
    void searchPoliciesAsAgentReturnsOnlyThePolicyInTheSupervisorsDownline() throws Exception {
        Book book = buildBook("SEARCH-POL");

        mockMvc.perform(get("/policies").with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].policyNumber").value(book.policyInTeam()));
    }

    @Test
    void searchPoliciesAsStaffSeesEveryPolicyRegardlessOfAgent() throws Exception {
        Book book = buildBook("SEARCH-STAFF");

        mockMvc.perform(get("/policies").with(staffOf(book.tenantId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3));
    }

    // ============================================================================================
    // GET /policies/{policyNumber} -- single-resource scoping
    // ============================================================================================

    @Test
    void getPolicyAsAgentReturns200ForAPolicyInItsOwnDownline() throws Exception {
        Book book = buildBook("GET-OK");

        mockMvc.perform(get("/policies/{n}", book.policyInTeam()).with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policyNumber").value(book.policyInTeam()));
    }

    @Test
    void getPolicyAsAgentReturns403ForAPolicyOutsideItsTeam() throws Exception {
        Book book = buildBook("GET-403");

        // policyOutsideTeam is REAL and in the SAME tenant -- a broken ownership check returns
        // 200 here, not an incidental 404.
        mockMvc.perform(get("/policies/{n}", book.policyOutsideTeam()).with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void getPolicyAsAgentReturns403ForADirectSoldPolicyWithNoAgentOfRecord() throws Exception {
        Book book = buildBook("GET-DIRECT");

        // No "unattributed" bucket an agent is entitled to browse -- a null agentOfRecordId is
        // never a match, even for an otherwise-legitimate agent token.
        mockMvc.perform(get("/policies/{n}", book.policyDirectSold()).with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void getPolicyAsAgentReturns200WhenTheCallerIsTheDirectAgentOfRecordNotJustTheSupervisor() throws Exception {
        Book book = buildBook("GET-SELF");

        mockMvc.perform(get("/policies/{n}", book.policyInTeam()).with(agentOf(book.tenantId(), book.subordinate().partyId())))
            .andExpect(status().isOk());
    }

    @Test
    void getPolicyAsStaffReturns200RegardlessOfAgent() throws Exception {
        Book book = buildBook("GET-STAFF");

        mockMvc.perform(get("/policies/{n}", book.policyOutsideTeam()).with(staffOf(book.tenantId())))
            .andExpect(status().isOk());
    }

    // ============================================================================================
    // GET /claims -- search scoping (joins through policy, claims has no agentOfRecordId itself)
    // ============================================================================================

    @Test
    void searchClaimsAsAgentReturnsOnlyTheClaimAgainstAPolicyInItsDownline() throws Exception {
        Book book = buildBook("SEARCH-CLAIM");

        mockMvc.perform(get("/claims").with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].claimId").value(book.claimInTeam().toString()));
    }

    // ============================================================================================
    // GET /claims/{claimId} -- single-resource scoping
    // ============================================================================================

    @Test
    void getClaimAsAgentReturns200ForAClaimAgainstAPolicyInItsOwnDownline() throws Exception {
        Book book = buildBook("CLAIM-OK");

        mockMvc.perform(get("/claims/{id}", book.claimInTeam()).with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isOk());
    }

    @Test
    void getClaimAsAgentReturns403ForAClaimOutsideItsTeam() throws Exception {
        Book book = buildBook("CLAIM-403");

        mockMvc.perform(get("/claims/{id}", book.claimOutsideTeam()).with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void getClaimAsStaffReturns200RegardlessOfAgent() throws Exception {
        Book book = buildBook("CLAIM-STAFF");

        mockMvc.perform(get("/claims/{id}", book.claimOutsideTeam()).with(staffOf(book.tenantId())))
            .andExpect(status().isOk());
    }

    // ============================================================================================
    // GET /claims/{claimId}/evidence/{documentRef} -- same scoping, via ClaimController's
    // enforceAgentOwnClaimOnly reused verbatim by ClaimEvidenceController. Not previously covered
    // by any test with the right migrations in place -- the pre-existing
    // ClaimEvidenceDownloadTest's own "an agent may download evidence for a claim belonging to a
    // different party" predates this scoping feature entirely (it asserted the OLD, now-closed
    // gap: an unscoped agents-realm token could read any evidence in the tenant) and never had
    // `distribution` schema migrations applied, so it 500'd instead of the correct 403 once this
    // scoping landed. Replaced here with real positive/negative coverage using this file's own
    // fixtures, which already have every migration this code path needs.
    // ============================================================================================

    @Test
    void downloadEvidenceAsAgentReturns200ForAClaimAgainstAPolicyInItsOwnDownline() throws Exception {
        Book book = buildBook("EVIDENCE-OK");
        String documentRef = attachEvidence(book.tenantId(), book.claimInTeam());

        mockMvc.perform(get("/claims/{id}/evidence/{ref}", book.claimInTeam(), documentRef)
                .with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void downloadEvidenceAsAgentReturns403ForAClaimOutsideItsTeam() throws Exception {
        Book book = buildBook("EVIDENCE-403");
        String documentRef = attachEvidence(book.tenantId(), book.claimOutsideTeam());

        mockMvc.perform(get("/claims/{id}/evidence/{ref}", book.claimOutsideTeam(), documentRef)
                .with(agentOf(book.tenantId(), book.supervisor().partyId())))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // The edge case that a naive implementation gets backwards: an agents-realm token whose party
    // is NOT actually an agent in this tenant must see NOTHING, not everything. This is exactly
    // the null-vs-empty-Set distinction PolicyApiImpl.searchPolicies/ClaimsApiImpl.searchClaims
    // both have to get right -- a non-null EMPTY team must filter to zero rows, not silently fall
    // through to the "no agent filter" branch and leak the whole tenant.
    // ============================================================================================

    @Test
    void searchPoliciesAsAnAgentsRealmTokenWithNoRealAgentProfileReturnsNothingNotEverything() throws Exception {
        Book book = buildBook("NOT-AGENT-POL");
        UUID randomPartyId = UUID.randomUUID(); // never onboarded as an agent at all

        mockMvc.perform(get("/policies").with(agentOf(book.tenantId(), randomPartyId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void searchClaimsAsAnAgentsRealmTokenWithNoRealAgentProfileReturnsNothingNotEverything() throws Exception {
        Book book = buildBook("NOT-AGENT-CLAIM");
        UUID randomPartyId = UUID.randomUUID();

        mockMvc.perform(get("/claims").with(agentOf(book.tenantId(), randomPartyId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }
}
