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

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql");
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

    private ProductFixture publishTestProduct(UUID tenantId) throws Exception {
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"UW-CONTRACT-%s","productName":"UW Contract Test","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
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
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
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
}
