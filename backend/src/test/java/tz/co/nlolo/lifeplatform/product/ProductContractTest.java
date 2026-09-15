package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ProductContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-product.yaml";

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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createProductMatchesOpenApiContract() throws Exception {
        mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-01","productName":"Contract Term Life","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void listProductsMatchesOpenApiContract() throws Exception {
        // A brand-new tenant with zero products would make listActiveProducts return [] --
        // OpenAPI validation would only ever check "is an empty array valid," never a real
        // ProductSummary item's fields/enums. So seed a real ACTIVE product (create + publish
        // a version, since publishVersion is what flips a product from DRAFT to ACTIVE) under
        // the same tenant before listing, and assert on that item's presence/fields too.
        UUID tenantId = UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-LIST-01","productName":"Contract Listed Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        ProductSummaryView created = objectMapper.readValue(createResult.getResponse().getContentAsString(), ProductSummaryView.class);
        UUID productId = created.productId();

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')]").exists())
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].category").value("TERM_LIFE"))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].status").value("ACTIVE"))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].defaultCurrency").value("TZS"));
    }

    @Test
    void createProductRejectsNonStaffCaller() throws Exception {
        // POST /products is @PreAuthorize("hasRole('REALM_STAFF')") -- a customer or agent JWT
        // must be rejected with 403, not silently allowed through. Regression coverage for the
        // staff-only authoring boundary described in openapi-product.yaml's module description.
        mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-FORBIDDEN-01","productName":"Should Be Rejected","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void publishVersionRejectsNonStaffCaller() throws Exception {
        // POST /products/{productId}/versions is also @PreAuthorize("hasRole('REALM_STAFF')").
        // Create the product as staff (so the request reaches the authorization check on the
        // versions endpoint against a real product, not a 404 short-circuit), then attempt to
        // publish a version as a customer -- must be rejected with 403.
        UUID tenantId = UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-FORBIDDEN-02","productName":"Contract Forbidden Version","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        ProductSummaryView created = objectMapper.readValue(createResult.getResponse().getContentAsString(), ProductSummaryView.class);
        UUID productId = created.productId();

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void publishVersionAndActiveSnapshotMatchOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-02","productName":"Contract Endowment","category":"ENDOWMENT","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        ProductSummaryView created = objectMapper.readValue(createResult.getResponse().getContentAsString(), ProductSummaryView.class);
        UUID productId = created.productId();

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * A product in the catalogue with no version in force TODAY.
     *
     * <p>Found in the dev tenant, not imagined: a GROUP_LIFE product whose only version was
     * effective the following day appeared in the products list -- publishing flips the
     * definition to ACTIVE whatever the effective date -- and opening it reported "this
     * record does not exist, or it is not available to your role" to a staff member who had
     * just clicked it. Both halves false. The cause was one exception answering two
     * questions, with the version lookup running before the existence check.
     *
     * <p>The assertion that matters is the errorCode, because the STATUS is unchanged: a
     * client that cannot tell this from a missing product has to hedge about the caller's
     * role, and hedging is what produced the sentence above.
     */
    @Test
    void aProductWhoseOnlyVersionStartsLaterIsNotReportedAsAMissingProduct() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProduct(tenantId, "CONTRACT-FUTURE-VER", "Future Version Product");
        LocalDate startsTomorrow = LocalDate.now().plusDays(1);

        publishVersionEffective(tenantId, productId, startsTomorrow);

        // It is in the catalogue: publishing made it ACTIVE, so the console lists it and a
        // staff member can click it. That is the whole reason the detail must not deny it.
        mockMvc.perform(get("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')]").exists());

        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // NOT PRODUCT_NOT_FOUND. This is the defect, pinned.
            .andExpect(jsonPath("$.errorCode").value("NO_ACTIVE_PRODUCT_VERSION"))
            // The remedy is a date, and it is guessable from nothing else.
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(startsTomorrow.toString())))
            .andExpect(jsonPath("$.traceId").exists());

        // And asking as of the day it starts resolves normally -- proof the version is
        // real and only the DATE was wrong, rather than the product being broken.
        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .queryParam("effectiveDate", startsTomorrow.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.effectiveDate").value(startsTomorrow.toString()));
    }

    /**
     * The other half, and the regression guard for the reordering: a product that genuinely
     * does not exist must still say so. Resolving the definition before the version is what
     * keeps these two answers distinct, and swapping them back would make this fail.
     */
    @Test
    void aProductThatDoesNotExistStillSaysProductNotFound() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/products/" + UUID.randomUUID() + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }

    /**
     * A DRAFT product -- created, never published -- which is the same absence with no next
     * date to offer, and covers the other branch of the exception's message.
     *
     * <p>It is also the more common way to meet this: a product is DRAFT until a version is
     * published, so anything reaching this id before that (a bookmark, a link from an
     * authoring flow, a direct URL) asks for a snapshot that cannot exist yet. Saying "no
     * such product" of a product somebody just created is the same lie in a different
     * place.
     */
    @Test
    void aDraftProductWithNoVersionsReportsNoActiveVersionAndNoNextDate() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProduct(tenantId, "CONTRACT-DRAFT-VER", "Draft Version Product");

        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("NO_ACTIVE_PRODUCT_VERSION"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no later version is scheduled")));
    }

    /** Creates a DRAFT product and returns its id. */
    private UUID createProduct(UUID tenantId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"" + code + "\",\"productName\":\"" + name
                    + "\",\"category\":\"ENDOWMENT\",\"defaultCurrency\":\"TZS\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ProductSummaryView.class)
            .productId();
    }

    /** Publishes one version effective on the given date, which is what makes a product ACTIVE. */
    private void publishVersionEffective(UUID tenantId, UUID productId, LocalDate effectiveDate)
            throws Exception {
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ifrsMeasurementModel\":\"PAA\",\"effectiveDate\":\"" + effectiveDate + "\","
                    + "\"tiraFiling\":{\"reference\":\"TIRA/CONTRACT/0002\",\"approvalDate\":\"2026-01-15\"},"
                    + "\"ratingTable\":[{\"factorType\":\"AGE\",\"band\":\"30-39\",\"multiplier\":1.0,\"ageFrom\":30,\"ageTo\":39},"
                    + "{\"factorType\":\"SUM_ASSURED_BAND\",\"band\":\"LOW\",\"multiplier\":1.0}],"
                    + "\"benefitSchedule\":[{\"benefitType\":\"MATURITY\",\"calculationMethod\":\"SUM_ASSURED_PLUS_BONUS\"}]}"))
            .andExpect(status().isCreated());
    }

    @Test
    void publishVersionRejectsMissingRatingCoverageWithUnprocessableEntity() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CONTRACT-03","productName":"Contract Incomplete","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        ProductSummaryView created = objectMapper.readValue(createResult.getResponse().getContentAsString(), ProductSummaryView.class);
        UUID productId = created.productId();

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("INVALID_PRODUCT_VERSION"));
    }
    // --- Authoring is ADMIN, and reading is not ------------------------------------------------
    //
    // These two endpoints were hasRole('REALM_STAFF') until now, so any staff member -- an
    // underwriter, a claims assessor -- could create and price a product. PRODUCT.md described
    // ADMIN as "everything a finance officer sees, plus product authoring and configuration",
    // which was false in both directions: authoring was open to everyone, and ADMIN carried no
    // capability FINANCE_OFFICER lacked anywhere on the platform.
    //
    // Each test below pairs the denial with the SAME call succeeding for ADMIN. A 403 test on
    // its own would still pass if the endpoint had been broken outright.

    @Test
    void creatingAProductIsRefusedForAStaffMemberWhoIsNotAnAdmin() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"GATE-UW-01","productName":"Underwriter Authored","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isForbidden());

        // FINANCE_OFFICER is refused too, deliberately: pricing a life product is actuarial
        // set-up, and widening this to the finance pair used elsewhere would leave ADMIN with no
        // capability of its own again -- the exact state this change exists to end.
        mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"GATE-FIN-01","productName":"Finance Authored","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isForbidden());

        // The same call, as ADMIN, succeeds -- so the two above are a gate and not a breakage.
        mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"GATE-ADMIN-01","productName":"Admin Authored","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated());
    }

    @Test
    void publishingAVersionIsRefusedForAStaffMemberWhoIsNotAnAdmin() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"GATE-PUB-01","productName":"Gate Publish","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"GMM","effectiveDate":"2026-01-01",
                     "tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"ageBandStart":18,"ageBandEnd":65,"gender":"ANY","ratePerMille":"3.50"}],
                     "benefitSchedule":[{"benefitType":"DEATH","basis":"MULTIPLE_OF_SUM_ASSURED","factor":"1.0"}]}
                    """))
            .andExpect(status().isForbidden());
    }

    /**
     * A SUM ASSURED BAND'S BOUNDS SURVIVE THE WIRE, publish to read.
     *
     * <p><b>This is the test that would have caught the change not working at all.</b> The
     * bounds existed on {@code RatingFactorInput}, the resolver matched on them, and
     * {@code ProductApiIntegrationTest} proved every rule about them — while
     * {@code RatingFactorRequest} did not carry the two fields, so Jackson dropped them off
     * every publish that arrived over HTTP. Every real publish comes over HTTP. The console
     * would have collected the amounts, sent them, shown no error, and stored rows with NULL
     * bounds: the original defect, arriving through a screen that appeared to have fixed it,
     * with a green suite behind it.
     *
     * <p>It asserts on the READ rather than on a 201, for the same reason: a publish that
     * silently discards half its body is still a 201. The rating basis endpoint is where an
     * actuary checks their own table, so it is also where a dropped bound becomes visible.
     */
    @Test
    void sumAssuredBandBoundsSurviveAPublishOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"SA-BOUNDS-01","productName":"Banded By Amount","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        // The multiplier is 1.25 and not 1.0 on purpose: publish-time validation only demands
        // bounds of a band that RATES, so a neutral row would be accepted with or without them
        // and would prove nothing about whether they arrived.
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"Up to 5m","multiplier":1.25,
                                     "sumAssuredFrom":0,"sumAssuredTo":5000000}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        String snapshot = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(snapshot, "$.productVersionId");

        String rating = mockMvc.perform(get("/products/" + productId + "/versions/" + versionId + "/rating")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();

        // Compared as BigDecimal rather than asserted on the literal: the column is
        // numeric(18,2), so the wire carries 0.00 and 5000000.00 and a comparison against 0
        // would fail on scale while the value it is checking is perfectly correct.
        List<Object> from = JsonPath.read(rating,
            "$.ratingFactors[?(@.factorType == 'SUM_ASSURED_BAND')].sumAssuredFrom");
        List<Object> to = JsonPath.read(rating,
            "$.ratingFactors[?(@.factorType == 'SUM_ASSURED_BAND')].sumAssuredTo");
        assertEquals(1, from.size(), "exactly one sum assured band was published");
        assertEquals(0, new BigDecimal(String.valueOf(from.get(0))).compareTo(BigDecimal.ZERO),
            "the lower bound was dropped somewhere between the console and the database");
        assertEquals(0, new BigDecimal(String.valueOf(to.get(0))).compareTo(new BigDecimal("5000000")),
            "the upper bound was dropped somewhere between the console and the database");
    }

    /**
     * The other half of the same change: every product READ stays open to staff. Issuing a
     * policy needs the catalogue, the active snapshot and a premium quote, so a gate that also
     * caught the reads would have blocked underwriting for the roles that must never be blocked
     * from it. Asserted rather than assumed, because "tighten the product endpoints" is exactly
     * the kind of instruction that takes the reads with it.
     */
    @Test
    void readingTheProductCatalogueStaysOpenToAnyStaffMember() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk());
    }
}
