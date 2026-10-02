package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
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

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class UnderwritingContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-underwriting.yaml";

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
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
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
            "db-migrations/underwriting/V13__single_premium_frequency.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired
    private MockMvc mockMvc;

    private String openCaseViaHttp(UUID tenantId, UUID applicantPartyId, UUID productId, UUID productVersionId) throws Exception {
        return mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantPartyId, productId, productVersionId)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
    }

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    /**
     * A scheme proposed over HTTP: the wire shape, the role gate, and the two things the case
     * must and must not carry.
     *
     * <p>Its own endpoint because POST /underwriting/cases requires a sumAssured and a group
     * case has none — the figure appears only when policy derives it from the schedule at
     * issuance.
     */
    @Test
    void proposingAGroupSchemeOverHttpMatchesTheSpecAndCarriesNoSumAssured() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID employer = registerTestApplicant(tenantId);
        UUID life = registerTestApplicant(tenantId);
        ProductFixture product = publishGroupTestProduct(tenantId);
        String body = """
            {"policyholderPartyId":"%s","productId":"%s","productVersionId":"%s",
             "benefitBasis":"FLAT","flatBenefitAmount":"5000000.00","currency":"TZS",
             "openingSchedule":[{"memberPartyId":"%s"}],
             "premiumAmount":"1200000.00","premiumCurrency":"TZS","premiumFrequency":"ANNUALLY"}
            """.formatted(employer, product.productId(), product.productVersionId(), life);

        // Proposing a scheme is the front of the act that ends in a contract on risk, so it
        // takes the same role that act does. A plain staff token is not enough.
        mockMvc.perform(post("/underwriting/cases/group")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());

        String response = mockMvc.perform(post("/underwriting/cases/group")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.groupScheme").value(true))
            // NULL, and asserted: the queue must not show a company against a figure nobody
            // computed. policy derives it at issuance, in the module that owns the calculator.
            .andExpect(jsonPath("$.sumAssuredAmount").doesNotExist())
            .andExpect(jsonPath("$.sumAssured").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(response, "$.caseId");

        // The proposal is its own sub-resource: a 500-row schedule has no business on every
        // row of a queue page.
        mockMvc.perform(get("/underwriting/cases/" + caseId + "/group-proposal")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.benefitBasis").value("FLAT"))
            .andExpect(jsonPath("$.openingSchedule.length()").value(1))
            .andExpect(jsonPath("$.premiumAmount").value("1200000.00"));
    }

    @Test
    void anIndividualCaseHasNoGroupProposalToFetch() throws Exception {
        // 404, not an empty body: there is no proposal, and the URL resolving to nothing is
        // the plain way to say so.
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseId = JsonPath.read(
            openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId()), "$.caseId");

        mockMvc.perform(get("/underwriting/cases/" + caseId + "/group-proposal")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotFound());
    }

    /** A GROUP_LIFE product, since a group proposal is refused against any other category. */
    private ProductFixture publishGroupTestProduct(UUID tenantId) throws Exception {
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"UW-GRP-%s","productName":"UW Group Contract Test","category":"GROUP_LIFE","defaultCurrency":"TZS"}
                    """.formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

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
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new ProductFixture(UUID.fromString(productId),
            UUID.fromString(JsonPath.read(snapshotResponse, "$.productVersionId")));
    }

    private ProductFixture publishTestProduct(UUID tenantId) throws Exception {
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"UW-CONTRACT-%s","productName":"UW Contract Test","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

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
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String productVersionId = JsonPath.read(snapshotResponse, "$.productVersionId");

        return new ProductFixture(UUID.fromString(productId), UUID.fromString(productVersionId));
    }

    private UUID registerTestApplicant(UUID tenantId) throws Exception {
        String response = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"UW Contract Applicant","dateOfBirth":"1988-03-15","contactInfo":{"phoneNumber":"+255712340000"}}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        // PartyView's JSON field is "partyId" (party/api/PartyView.java), not "id".
        return UUID.fromString(JsonPath.read(response, "$.partyId"));
    }

    @Test
    void openCaseMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
    }

    @Test
    void getCaseMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseResponse = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        mockMvc.perform(get("/underwriting/cases/" + caseId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // What the issue form prefills from, so nobody types the sum assured twice.
            .andExpect(jsonPath("$.sumAssured.amount").value("1000000.00"))
            .andExpect(jsonPath("$.sumAssured.currencyCode").value("TZS"));
        // And on the list the issue form's case picker reads.
        mockMvc.perform(get("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items[0].sumAssured.amount").value("1000000.00"));
    }

    @Test
    void submitAssessmentMatchesOpenApiContractAndRequiresUnderwriterRole() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseResponse = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        // A plain staff token without the UNDERWRITER role is forbidden.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/assessments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assessmentType":"MEDICAL","findings":"Routine","riskScore":10}
                    """))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/underwriting/cases/" + caseId + "/assessments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assessmentType":"MEDICAL","findings":"Routine","riskScore":10}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * The decision endpoint, and the senior gate on an override, over real HTTP.
     *
     * <p>The 403 half is the point. A junior may record the decision the engine recommended and
     * may not record any other, and that distinction is invisible to {@code @PreAuthorize} —
     * it depends on the recommendation currently sitting on the case, so only the service can
     * make it. A role annotation alone would let either decision through.
     */
    @Test
    void decisionMatchesOpenApiContractAndAnOverrideNeedsASenior() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseResponse = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        // A low risk score, so the engine recommends ACCEPT.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/assessments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assessmentType":"MEDICAL","findings":"Routine","riskScore":10}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.recommendationOutcome").value("ACCEPT"))
            .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        // The person who assessed it may not decide it -- even a senior, and even in line with
        // the engine. Every token above carries the same default subject, so this is that person.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/decision")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"),
                        new SimpleGrantedAuthority("ROLE_SENIOR_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"outcome":"ACCEPT","reason":"Routine"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("UNDERWRITING_SEPARATION_OF_DUTIES"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        // DECLINED departs from that recommendation, and this caller is not senior.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/decision")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.subject("ct-decider").claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"outcome":"DECLINED","reason":"Adverse history disclosed off-system"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("SENIOR_UNDERWRITER_APPROVAL_REQUIRED"));

        // The same decision, from a senior, is accepted.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/decision")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"),
                        new SimpleGrantedAuthority("ROLE_SENIOR_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.subject("ct-senior").claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"outcome":"DECLINED","reason":"Adverse history disclosed off-system"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DECIDED"))
            .andExpect(jsonPath("$.decisionOverrodeRecommendation").value(true))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * The proposal's contract terms and nominations round-trip over real HTTP.
     *
     * <p>Worth a contract test of its own rather than trusting the integration tests, because
     * the gap this closes was exactly a wire-layer one: the columns, the service and the
     * issuance listener were all done while {@code OpenCaseRequest} and the controller still
     * used the four-argument {@code ProposalDetails}, so the API silently accepted none of it.
     * Everything below the HTTP boundary would have passed.
     */
    @Test
    void openCaseCarriesTheProposalsTermsAndNominationsOverTheWire() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);

        mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "requestedTermMonths":120,"premiumPayingTermMonths":60,"premiumFrequency":"QUARTERLY",
                     "beneficiaries":[{"type":"FREEFORM","freeformDesignee":"The estate","sharePercent":100}]}
                    """.formatted(applicantId, product.productId(), product.productVersionId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requestedTermMonths").value(120))
            .andExpect(jsonPath("$.premiumPayingTermMonths").value(60))
            .andExpect(jsonPath("$.premiumFrequency").value("QUARTERLY"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * Every frequency the policy CHECK admits is accepted HERE too.
     *
     * <p>Written because SINGLE was not. It was admitted by {@code
     * policy_premium_frequency_check}, by {@code PremiumFrequency}, by this module's own
     * OpenAPI schema and by the console, and was still refused by a {@code @Pattern} on
     * {@link tz.co.nlolo.lifeplatform.underwriting.infrastructure.OpenCaseRequest} that nobody
     * had a reason to look at. The failure surfaced as a bare "Request validation failed" with
     * a trace id that is generated per response and never logged, so it could not be looked up
     * either. Looping over the frequencies means the next one added cannot repeat that.
     */
    @Test
    void everyPremiumFrequencyTheColumnAdmitsIsAcceptedWhenOpeningACase() throws Exception {
        for (String frequency : List.of("MONTHLY", "QUARTERLY", "ANNUALLY", "SINGLE")) {
            UUID tenantId = UUID.randomUUID();
            UUID applicantId = registerTestApplicant(tenantId);
            ProductFixture product = publishTestProduct(tenantId);

            mockMvc.perform(post("/underwriting/cases")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                        .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s",
                         "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                         "requestedTermMonths":12,"premiumFrequency":"%s",
                         "beneficiaries":[{"type":"FREEFORM","freeformDesignee":"The estate","sharePercent":100}]}
                        """.formatted(applicantId, product.productId(), product.productVersionId(), frequency)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.premiumFrequency").value(frequency))
                .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
        }
    }

    /**
     * The nominations sub-resource, which the console reads before a manual issuance.
     *
     * <p>Without it that form sends its own (empty) beneficiary list and the proposal's
     * nominations are silently dropped — by the very path meant to honour them.
     */
    @Test
    void nominationsAreReadableAsTheirOwnSubResource() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);

        String caseResponse = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "beneficiaries":[{"type":"FREEFORM","freeformDesignee":"The estate","sharePercent":100,"revocable":false}]}
                    """.formatted(applicantId, product.productId(), product.productVersionId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        mockMvc.perform(get("/underwriting/cases/" + caseId + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].type").value("FREEFORM"))
            .andExpect(jsonPath("$[0].freeformDesignee").value("The estate"))
            .andExpect(jsonPath("$[0].revocable").value(false))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /** A malformed nomination is refused at the boundary, not stored and discovered later. */
    @Test
    void nominationsThatDoNotTotalOneHundredAreRefusedOverTheWire() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);

        mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "beneficiaries":[{"type":"FREEFORM","freeformDesignee":"Half only","sharePercent":50}]}
                    """.formatted(applicantId, product.productId(), product.productVersionId())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("UNDERWRITING_VALIDATION_FAILED"));
    }

    @Test
    void referralMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseResponse = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String caseId = JsonPath.read(caseResponse, "$.caseId");

        mockMvc.perform(post("/underwriting/cases/" + caseId + "/referral")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_UNDERWRITER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk());
    }

    @Test
    void getCaseForNonexistentCaseReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/underwriting/cases/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("UNDERWRITING_CASE_NOT_FOUND"));
    }

    @Test
    void openCaseReturns404ForANonexistentApplicantPartyId() throws Exception {
        // productId/productVersionId are random, deliberately -- applicantPartyId must be checked
        // and 404 first, before either of those is resolved.
        UUID tenantId = UUID.randomUUID();
        UUID nonexistentPartyId = UUID.randomUUID();

        mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(nonexistentPartyId, UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isNotFound());
    }

    @Test
    void listCasesDefaultsToNewestCreatedFirst() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);

        String firstCase = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String firstCaseId = JsonPath.read(firstCase, "$.caseId");
        String secondCase = openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        String secondCaseId = JsonPath.read(secondCase, "$.caseId");

        mockMvc.perform(get("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].caseId").value(secondCaseId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[1].caseId").value(firstCaseId))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void listCasesFiltersByStatus() throws Exception {
        // Two cases in the SAME tenant, both left OPEN (nothing here decides
        // either) -- if the status filter were silently ignored, filtering to
        // DECIDED would still wrongly return these 2 OPEN cases instead of 0.
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());

        mockMvc.perform(get("/underwriting/cases")
                .param("status", "OPEN")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(get("/underwriting/cases")
                .param("status", "DECIDED")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void listCasesIsTenantScoped() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId());

        UUID otherTenantId = UUID.randomUUID();
        UUID otherApplicantId = registerTestApplicant(otherTenantId);
        ProductFixture otherProduct = publishTestProduct(otherTenantId);
        openCaseViaHttp(otherTenantId, otherApplicantId, otherProduct.productId(), otherProduct.productVersionId());

        mockMvc.perform(get("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.totalElements").value(1));
    }

    // --- Disclosures ------------------------------------------------------------------------

    @Test
    void recordAndListDisclosuresMatchTheOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseId = JsonPath.read(
            openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId()), "$.caseId");

        // Recorded by an AGENT, not an underwriter: taking a proposal is not underwriting it,
        // and the person who asked the questions is the one who writes down the answers.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/disclosures")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"answers":[{"questionCode":"Q1","question":"Have you ever been treated for heart disease?","answer":"No"},
                                {"questionCode":"Q2","question":"Do you smoke?","answer":"Yes, 10 a day","notes":"Volunteered"}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/underwriting/cases/" + caseId + "/disclosures")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$[0].answers.length()").value(2))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$[0].answers[0].question").value("Have you ever been treated for heart disease?"));
    }

    @Test
    void aDisclosureSetWithNoAnswersIsRejectedWith422() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseId = JsonPath.read(
            openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId()), "$.caseId");

        // 400, not 422: @NotEmpty on the request body means Bean Validation rejects the
        // request shape before any domain code runs, and this platform maps that to 400. The
        // domain's own UnderwritingValidationException is the 422 -- it is reachable through
        // UnderwritingApi directly (see anEmptyDisclosureSetIsRefused) and is the backstop for
        // any caller that is not this controller.
        mockMvc.perform(post("/underwriting/cases/" + caseId + "/disclosures")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anAnswerWithNoQuestionTextIsRejected() throws Exception {
        // The wording is what a contest turns on. A row carrying a code and an answer but no
        // question looks like evidence and is not.
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerTestApplicant(tenantId);
        ProductFixture product = publishTestProduct(tenantId);
        String caseId = JsonPath.read(
            openCaseViaHttp(tenantId, applicantId, product.productId(), product.productVersionId()), "$.caseId");

        mockMvc.perform(post("/underwriting/cases/" + caseId + "/disclosures")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[{\"questionCode\":\"Q1\",\"question\":\"\",\"answer\":\"No\"}]}"))
            .andExpect(status().isBadRequest());
    }

    // --- Agent scoping, which this endpoint had none of --------------------------------------
    //
    // `GET /underwriting/cases` has admitted REALM_AGENTS since M4 while taking no JWT and applying
    // no filter, so any agents-realm token could list every case in the tenant -- each with its
    // applicant, sum assured and decision. It was found while adding the applicantPartyId filter
    // the client register needs, which would have handed that hole a precise aim.

    /** Registers an applicant as a specific agent subject, so `createdBy` is that agent. */
    private UUID registerApplicantAs(UUID tenantId, String agentSubject, String phone) throws Exception {
        String response = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(agentSubject).claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                // An agent must record sex and an identity document; the ID number is derived
                // from the phone because ux_party_individual_identity refuses a shared one.
                .content("{\"fullName\":\"Scoped UW Applicant\",\"dateOfBirth\":\"1988-03-15\","
                    + "\"sex\":\"FEMALE\",\"idType\":\"NATIONAL_ID\","
                    + "\"idNumber\":\"19880315-" + phone.substring(phone.length() - 5) + "-00001-11\","
                    + "\"contactInfo\":{\"phoneNumber\":\"" + phone + "\"}}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.partyId"));
    }

    @Test
    void listCasesFiltersByApplicantPartyId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ProductFixture product = publishTestProduct(tenantId);
        UUID mine = registerApplicantAs(tenantId, "uw-agent-1", "+255712340101");
        UUID other = registerApplicantAs(tenantId, "uw-agent-1", "+255712340102");
        openCaseViaHttp(tenantId, mine, product.productId(), product.productVersionId());
        openCaseViaHttp(tenantId, other, product.productId(), product.productVersionId());

        mockMvc.perform(get("/underwriting/cases")
                .queryParam("applicantPartyId", mine.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.page.totalElements").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.items[0].applicantPartyId").value(mine.toString()));
    }

    @Test
    void anAgentSeesOnlyCasesForApplicantsItRegistered() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ProductFixture product = publishTestProduct(tenantId);
        UUID ownClient = registerApplicantAs(tenantId, "uw-agent-a", "+255712340103");
        UUID otherAgentsClient = registerApplicantAs(tenantId, "uw-agent-b", "+255712340104");
        openCaseViaHttp(tenantId, ownClient, product.productId(), product.productVersionId());
        openCaseViaHttp(tenantId, otherAgentsClient, product.productId(), product.productVersionId());

        // Two cases exist in the tenant; agent A must see exactly its own.
        mockMvc.perform(get("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("uw-agent-a").claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.page.totalElements").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.items[0].applicantPartyId").value(ownClient.toString()));
    }

    /**
     * Force-scoped, not check-then-reject: asking for another agent's client by id must return
     * nothing rather than that client's case. This is the assertion that proves the scope is applied
     * ON TOP of the caller's own filter instead of instead of it.
     */
    @Test
    void anAgentCannotBroadenItsScopeBySupplyingAnotherAgentsApplicantId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ProductFixture product = publishTestProduct(tenantId);
        UUID otherAgentsClient = registerApplicantAs(tenantId, "uw-agent-d", "+255712340105");
        openCaseViaHttp(tenantId, otherAgentsClient, product.productId(), product.productVersionId());

        mockMvc.perform(get("/underwriting/cases")
                .queryParam("applicantPartyId", otherAgentsClient.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("uw-agent-c").claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.page.totalElements").value(0));
    }

    /**
     * The empty-set semantic, which is the one that silently breaks. An agent who has registered
     * nobody must scope to NOTHING; a null passed here instead would mean "no scope" and hand that
     * agent every case in the tenant -- the exact bug this whole change removes.
     */
    @Test
    void anAgentWhoHasRegisteredNobodySeesNoCasesRatherThanAllOfThem() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ProductFixture product = publishTestProduct(tenantId);
        UUID someoneElsesClient = registerApplicantAs(tenantId, "uw-agent-e", "+255712340106");
        openCaseViaHttp(tenantId, someoneElsesClient, product.productId(), product.productVersionId());

        mockMvc.perform(get("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("uw-agent-with-no-clients")
                        .claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.page.totalElements").value(0));
    }
}
