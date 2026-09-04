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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

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
            "db-migrations/product/V6__eligibility_bounds.sql");
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
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
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void publishVersionAndActiveSnapshotMatchOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ifrsMeasurementModel\":\"PAA\",\"effectiveDate\":\"" + effectiveDate + "\","
                    + "\"ratingTable\":[{\"factorType\":\"AGE\",\"band\":\"30-39\",\"multiplier\":1.0,\"ageFrom\":30,\"ageTo\":39},"
                    + "{\"factorType\":\"SUM_ASSURED_BAND\",\"band\":\"LOW\",\"multiplier\":1.0}],"
                    + "\"benefitSchedule\":[{\"benefitType\":\"MATURITY\",\"calculationMethod\":\"SUM_ASSURED_PLUS_BONUS\"}]}"))
            .andExpect(status().isCreated());
    }

    @Test
    void publishVersionRejectsMissingRatingCoverageWithUnprocessableEntity() throws Exception {
        UUID tenantId = UUID.randomUUID();
        MvcResult createResult = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
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
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED_PLUS_BONUS"}]}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("INVALID_PRODUCT_VERSION"));
    }
}
