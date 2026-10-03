package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M12 Task 1: GET /policies and GET /policies/{n}/coverage-status become customer-reachable.
 *
 * <p>The two "ignores client-supplied" tests are the ones that matter. A check-then-reject
 * implementation passes the happy-path test and fails these -- a customer must never be able to
 * widen their own scope by supplying someone else's policyholderPartyId, and the correct behaviour
 * is to OVERRIDE the parameter, not to 403 on it, because a list endpoint has no single resource
 * to deny. Same reasoning as ClaimController.listClaims' javadoc.
 *
 * <p>Container/migration boilerplate copies {@code PolicyContractTest} exactly (same
 * {@code @BeforeAll} migration set) rather than the task brief's abbreviated snippet: the
 * schema Flyway builds for the policy module is otherwise absent, and {@code searchPolicies}/
 * {@code getCoverageStatus} run real queries against it even in these seed-free tests.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyCustomerScopingTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
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
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            // Needed since PolicyController.resolveOwnAgentTeamOrThrow (agents-realm "browse my
            // book of business" scoping) queries distribution.agent_profile for ANY agents-realm
            // search now, not just ones this class originally anticipated.
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql");
    }

    @Autowired
    MockMvc mockMvc;

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void customerTokenReachesPolicyList() throws Exception {
        mockMvc.perform(get("/policies")
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void customerListIgnoresClientSuppliedPolicyholderPartyId() throws Exception {
        UUID own = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        // Supplying a stranger's id must NOT widen scope. With zero policies seeded for `own`,
        // a correct force-scoped implementation returns an empty page rather than 403 or a leak.
        mockMvc.perform(get("/policies")
                .param("policyholderPartyId", someoneElse.toString())
                .with(customerOf(TENANT, own)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void customerTokenWithoutPartyIdClaimIsDenied() throws Exception {
        mockMvc.perform(get("/policies")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isForbidden());
    }

    /**
     * `party_id` is now REQUIRED on an agents-realm search (not just accepted-if-present) --
     * `PolicyController.resolveOwnAgentTeamOrThrow`, added by the agents-realm "browse my book of
     * business" scoping, needs it to resolve the caller's own team, the same way
     * `ownPartyIdOrThrow` already requires it for customers (see
     * `customerTokenWithoutPartyIdClaimIsDenied` above). This test predates that scoping feature;
     * its own explicit-filter assertion is still correct once a real party_id is supplied -- an
     * agent's own book scoping and an explicit policyholderPartyId filter apply together
     * (`effectivePolicyholderPartyId` is never overridden for agents the way it is for customers),
     * not either/or.
     */
    @Test
    void agentListStillAcceptsAnExplicitPolicyholderFilter() throws Exception {
        mockMvc.perform(get("/policies")
                .param("policyholderPartyId", UUID.randomUUID().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString())
                        .claim("party_id", UUID.randomUUID().toString()))))
            .andExpect(status().isOk());
    }

    /**
     * The symmetric case `customerTokenWithoutPartyIdClaimIsDenied` already covers for customers:
     * an agents-realm token that carries no party_id claim at all cannot resolve its own team, and
     * is denied rather than silently treated as having no scoping filter (which would leak the
     * whole tenant).
     */
    @Test
    void agentTokenWithoutPartyIdClaimIsDenied() throws Exception {
        mockMvc.perform(get("/policies")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotReadAnotherPartysCoverageStatus() throws Exception {
        // Verified (not assumed): getCoverageStatus calls policyApi.getPolicy(policyNumber)
        // BEFORE enforceCustomerOwnPolicyOnly, so a nonexistent policy number always 404s via
        // PolicyNotFoundException/findPolicyOrThrow before the ownership check ever runs --
        // same order as getPolicy's own cross-tenant anti-enumeration case. The exact status is
        // therefore always 404 POLICY_NOT_FOUND here, not merely "some 4xx".
        mockMvc.perform(get("/policies/POL-NONEXISTENT/coverage-status")
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    /**
     * I4 (final review, escalated deferred-minor #1): the test above only ever requests a
     * NONEXISTENT policy number, so it proves nothing about {@code enforceCustomerOwnPolicyOnly}
     * itself -- {@code policyApi.getPolicy} 404s before the ownership check is ever reached, and
     * all five tests in this class stayed green even with the ownership check deleted entirely.
     * These two tests seed a REAL policy owned by a REAL party (via the manual-issue flow, mirroring
     * {@code BillingCustomerPaymentTest.customerCannotRequestPaymentForAnotherPartysInvoice}) and
     * assert both halves: a stranger is 403'd, and the actual owner is let through with 200.
     */
    @Test
    void customerCannotReadAnotherPartysCoverageStatusForARealPolicy() throws Exception {
        IssuedPolicy issued = manualIssue(TENANT, "POLICY-SCOPING-COVERAGE-01");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/coverage-status")
                .with(customerOf(TENANT, UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    @Test
    void ownerCustomerCanReadTheirOwnCoverageStatus() throws Exception {
        IssuedPolicy issued = manualIssue(TENANT, "POLICY-SCOPING-COVERAGE-02");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/coverage-status")
                .with(customerOf(TENANT, issued.policyholderPartyId())))
            .andExpect(status().isOk());
    }

    private record IssuedPolicy(String policyNumber, UUID policyholderPartyId) {}

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    /**
     * Full-HTTP manual-issue fixture chain, copied from {@code PolicyContractTest.manualIssue} (and
     * mirroring {@code BillingCustomerPaymentTest.seedInvoiceOwnedBy}): register applicant -> publish
     * product -> open underwriting case -> manual-issue with an explicit premiumAmount. Runs against
     * the migration set already applied by {@link #applyMigrations()} -- no billing/policyloan schema
     * is touched during issuance.
     */
    private IssuedPolicy manualIssue(UUID tenantId, String productCode) throws Exception {
        String applicantResponse = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Policy Scoping Applicant","dateOfBirth":"1990-01-01","contactInfo":{"phoneNumber":"+255713%06d"}}
                    """.formatted(Math.abs(productCode.hashCode() % 1000000))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID applicantId = UUID.fromString(JsonPath.read(applicantResponse, "$.partyId"));

        String productResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Policy Scoping Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(productCode)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(productResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/TEST/0001","approvalDate":"2020-01-01"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        String caseResponse = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantId, productId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        String policyResponse = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"premiumFrequency":"MONTHLY",
                     "agentOfRecordId":null,"reasonForManualIssue":"Policy scoping test issuance"}
                    """.formatted(caseId, applicantId, productVersionId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(policyResponse, "$.policyNumber");

        return new IssuedPolicy(policyNumber, applicantId);
    }

    private static org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString())
                                   .claim("party_id", partyId.toString()));
    }
}
