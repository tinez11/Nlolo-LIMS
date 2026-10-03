package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.PayoutAmountBasis;
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

import static org.assertj.core.api.Assertions.assertThat;
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
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V22__annuity_terms.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql");
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
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED"}]}
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

    /**
     * PUT /products/{productId}/versions/{versionId}/exclusion-periods.
     *
     * <p>Until this endpoint existed the windows could only be set by writing SQL, which left the
     * claims exclusion gate inert in any real deployment: a version ships with both null, no
     * window is ever open, and every exclusion decline an assessor records is refused for citing
     * one that is not. Below the free cover limit nobody is underwritten, so on credit life these
     * two windows are the entire anti-selection control the product has.
     *
     * <p>Asserts the round trip through the WIRE, not through ProductApi. ProductSnapshotView
     * gained both fields in this branch and {@code getActiveSnapshot} returns that record
     * directly, so a field the view carries but the response does not would show up here and
     * nowhere else -- the exact shape of a bug this codebase has shipped before.
     */
    @Test
    void exclusionPeriodsRoundTripThroughTheWire() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProductAndPublishVersion(tenantId, "CONTRACT-EXCL-01", "Contract Exclusions");

        // Null before anything sets them -- the normal case for a product with no exclusions, and
        // the baseline that makes the assertion after the PUT mean something.
        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.suicideExclusionMonths").doesNotExist())
            .andExpect(jsonPath("$.preExistingExclusionMonths").doesNotExist());

        MvcResult snapshot = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andReturn();
        UUID versionId = objectMapper.readTree(snapshot.getResponse().getContentAsString())
            .path("productVersionId").traverse(objectMapper).readValueAs(UUID.class);

        // 12 and 12 -- what the client confirmed for credit life on 2026-09-22.
        mockMvc.perform(put("/products/" + productId + "/versions/" + versionId + "/exclusion-periods")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"suicideExclusionMonths":12,"preExistingExclusionMonths":12}
                    """))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.suicideExclusionMonths").value(12))
            .andExpect(jsonPath("$.preExistingExclusionMonths").value(12));

        // Sending one field CLEARS the other, deliberately: these are the version's exclusions as
        // a whole. Asserting it here because it is the behaviour most likely to be "fixed" into a
        // partial update by someone who has not read why.
        mockMvc.perform(put("/products/" + productId + "/versions/" + versionId + "/exclusion-periods")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"suicideExclusionMonths":24}
                    """))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.suicideExclusionMonths").value(24))
            .andExpect(jsonPath("$.preExistingExclusionMonths").doesNotExist());
    }

    @Test
    void exclusionPeriodsRejectAnImpossibleWindowAndANonAdminCaller() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProductAndPublishVersion(tenantId, "CONTRACT-EXCL-02", "Contract Exclusions Guard");
        MvcResult snapshot = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andReturn();
        UUID versionId = objectMapper.readTree(snapshot.getResponse().getContentAsString())
            .path("productVersionId").traverse(objectMapper).readValueAs(UUID.class);

        // 600 months is fifty years. Not a policy term -- a typo, whose cost is a claim declined
        // decades after any assessor would defend the decision.
        mockMvc.perform(put("/products/" + productId + "/versions/" + versionId + "/exclusion-periods")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"suicideExclusionMonths":600}
                    """))
            .andExpect(status().isBadRequest());

        // A version's exclusions are a priced term of the product, so the same ADMIN gate that
        // protects publishVersion protects this. Staff alone is not enough.
        mockMvc.perform(put("/products/" + productId + "/versions/" + versionId + "/exclusion-periods")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"suicideExclusionMonths":12,"preExistingExclusionMonths":12}
                    """))
            .andExpect(status().isForbidden());
    }

    /** Create + publish, the two steps every version-level test needs before it can say anything. */
    private UUID createProductAndPublishVersion(UUID tenantId, String code, String name) throws Exception {
        MvcResult createResult = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"" + code + "\",\"productName\":\"" + name
                    + "\",\"category\":\"CREDIT_LIFE\",\"defaultCurrency\":\"TZS\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        UUID productId = objectMapper.readValue(
            createResult.getResponse().getContentAsString(), ProductSummaryView.class).productId();

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());
        return productId;
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
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED"}]}
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
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED"}],
                     "payoutSchedule":[{"kind":"MATURITY","amountBasis":"PERCENT_OF_SA","amountValue":100}]}
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
    /**
     * The filing has to cross the wire and come back, which is the seam a service-level test
     * cannot see: {@code ProductApiIntegrationTest} constructs {@code TiraFiling} in Java and
     * never touches {@code ProductVersionSpec}. Batch 2a shipped {@code frequencyLoading} present
     * on both response schemas and absent from the REQUEST schema, with every backend test green
     * — the console typecheck was the only thing that caught it.
     *
     * <p>Asserted on the READ, not on the 201: a publish that silently discards half its body
     * still returns 201.
     */
    @Test
    void tiraFilingSurvivesAPublishOverHttpAndIsReadableBack() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"TIRA-WIRE-01","productName":"Filed Over Http","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/LIFE/2026/0777","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        String snapshot = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(snapshot, "$.productVersionId");

        mockMvc.perform(get("/products/" + productId + "/versions/" + versionId + "/rating")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tiraFiling.reference").value("TIRA/LIFE/2026/0777"))
            .andExpect(jsonPath("$.tiraFiling.approvalDate").value("2026-01-15"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Autowired
    private tz.co.nlolo.lifeplatform.product.api.ProductApi productApi;

    /**
     * A cash-value table authored over the wire is readable back (step 1). Before this the tables
     * had no write path at all -- only a raw SQL insert in one test -- so no product could ever
     * be published with surrender values, and nothing in the console could author one.
     */
    @Test
    void aCashValueTableSurvivesAPublishOverHttpAndIsReadableBack() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProduct(tenantId, "CV-WIRE-01", "Savings Over Http");   // ENDOWMENT

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"GMM","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/LIFE/2026/0901","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
                     "payoutSchedule":[{"kind":"MATURITY","amountBasis":"PERCENT_OF_SA","amountValue":100}],
                     "cashValue":{"basisReference":"ACT/2026/ENDOW-01","basisDate":"2026-01-10",
                                  "paidUpBasis":"PROPORTIONATE","minYearsForValue":2,
                                  "rows":[{"policyYear":2,"cashValuePerMille":200},{"policyYear":3,"cashValuePerMille":300}]}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        TenantContext.set(tenantId);
        UUID versionId = productApi.getActiveSnapshot(productId, LocalDate.of(2026, 6, 1)).productVersionId();
        assertThat(productApi.getCashValueConfig(versionId)).hasValueSatisfying(c -> {
            assertThat(c.basisReference()).isEqualTo("ACT/2026/ENDOW-01");
            assertThat(c.minYearsForValue()).isEqualTo(2);
        });
        assertThat(productApi.resolveCashValuePerMille(versionId, 3, null)).hasValueSatisfying(
            v -> assertThat(v).isEqualByComparingTo("300"));
    }

    @Test
    void aCashValueTableOnPureProtectionIsRefusedOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String productId = JsonPath.read(mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"CV-TERM-01","productName":"Term With Values","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/LIFE/2026/0902","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
                     "cashValue":{"basisReference":"ACT/2026/X","basisDate":"2026-01-10","paidUpBasis":"PROPORTIONATE",
                                  "minYearsForValue":2,"rows":[{"policyYear":2,"cashValuePerMille":200}]}}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("A TERM_LIFE product cannot carry a cash-value table"));
    }

    /** Product step 3: an ACCOUNT version's terms and charges arrive over HTTP and resolve back. */
    @Test
    void anAccountVersionSurvivesAPublishOverHttpAndIsReadableBack() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProduct(tenantId, "ACC-WIRE-01", "Account Over Http");   // ENDOWMENT

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"GMM","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/LIFE/2026/0911","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
                     "payoutSchedule":[{"kind":"MATURITY","amountBasis":"ACCOUNT_VALUE","amountValue":100}],
                     "accumulation":{"guaranteedRatePercent":3,"minimumBalance":50000,
                                     "charges":[{"fromPolicyYear":1,"toPolicyYear":1,"contributionAllocationPercent":5,
                                                 "transferAllocationPercent":0,"monthlyPolicyFee":1000},
                                                {"fromPolicyYear":2,"contributionAllocationPercent":1,
                                                 "transferAllocationPercent":0,"monthlyPolicyFee":1000}]}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        TenantContext.set(tenantId);
        UUID versionId = productApi.getActiveSnapshot(productId, LocalDate.of(2026, 6, 1)).productVersionId();
        AccumulationPlan plan = productApi.resolveAccumulationPlan(versionId);
        assertThat(plan.isAccount()).isTrue();
        assertThat(plan.guaranteedRatePercent()).isEqualByComparingTo("3");
        assertThat(plan.minimumBalance()).isEqualByComparingTo("50000");
        assertThat(plan.chargesFor(1).contributionAllocationPercent()).isEqualByComparingTo("5");
        // Year 7 falls in the open-ended row: every year after the last authored one has a charge.
        assertThat(plan.chargesFor(7).contributionAllocationPercent()).isEqualByComparingTo("1");
        assertThat(productApi.resolvePayoutPlan(versionId).rows()).singleElement()
            .satisfies(r -> assertThat(r.amountBasis()).isEqualTo(PayoutAmountBasis.ACCOUNT_VALUE));
    }

    @Test
    void anAccountBasisOnTermLifeIsRefusedOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String productId = JsonPath.read(mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"ACC-TERM-01","productName":"Term With An Account","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/LIFE/2026/0912","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
                     "accumulation":{"guaranteedRatePercent":3,"minimumBalance":0,
                                     "charges":[{"fromPolicyYear":1,"contributionAllocationPercent":0,
                                                 "transferAllocationPercent":0,"monthlyPolicyFee":0}]}}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("A TERM_LIFE product cannot use an account value basis"));
    }

    /** And a publish with no filing at all is refused at the edge, not deep in the service. */
    @Test
    void aPublishWithNoTiraFilingIsRefusedOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"TIRA-WIRE-02","productName":"Unfiled Over Http","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isBadRequest());
    }

    private void publishVersionEffective(UUID tenantId, UUID productId, LocalDate effectiveDate)
            throws Exception {
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ifrsMeasurementModel\":\"PAA\",\"effectiveDate\":\"" + effectiveDate + "\","
                    + "\"payoutTerms\":{\"freeLookDays\":15},"
                    + "\"payoutSchedule\":[{\"kind\":\"MATURITY\",\"amountBasis\":\"PERCENT_OF_SA\",\"amountValue\":100}],"
                    + "\"tiraFiling\":{\"reference\":\"TIRA/CONTRACT/0002\",\"approvalDate\":\"2026-01-15\"},"
                    + "\"ratingTable\":[{\"factorType\":\"AGE\",\"band\":\"30-39\",\"multiplier\":1.0,\"ageFrom\":30,\"ageTo\":39},"
                    + "{\"factorType\":\"SUM_ASSURED_BAND\",\"band\":\"LOW\",\"multiplier\":1.0}],"
                    + "\"benefitSchedule\":[{\"benefitType\":\"MATURITY\",\"calculationMethod\":\"SUM_ASSURED\"}]}"))
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
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39}],
                     "benefitSchedule":[{"benefitType":"MATURITY","calculationMethod":"SUM_ASSURED"}]}
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
                // A WELL-FORMED body, so the 403 can only be the authorisation gate.
                //
                // This sent invented field names -- ageBandStart, basis, factor, none of which
                // exist on the wire records -- and passed because nothing validated them. Once
                // benefitSchedule gained @Valid the body came back 400, and a gate test that a
                // malformed body can answer was never proving the gate.
                .content("""
                    {"ifrsMeasurementModel":"GMM","effectiveDate":"2026-01-01",
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
                     "ratingTable":[{"factorType":"AGE","band":"18-65","multiplier":"1.00","ageFrom":18,"ageTo":65},
                                    {"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":"1.00"}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
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
                     "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/0001","approvalDate":"2026-01-15"},
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
    // ---- Step 4: with-profits over the wire ----

    private UUID createProductOfCategory(UUID tenantId, String code, String category) throws Exception {
        MvcResult created = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productCode\":\"" + code + "\",\"productName\":\"" + code + "\",\"category\":\"" + category
                    + "\",\"defaultCurrency\":\"TZS\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readValue(created.getResponse().getContentAsString(), ProductSummaryView.class).productId();
    }

    private static String withProfitsVersion(String payoutSchedule) {
        return """
            {"ifrsMeasurementModel":"GMM","effectiveDate":"2026-01-01",
             "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/WP","approvalDate":"2026-01-15"},
             "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
             "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
             "payoutSchedule":%s,
             "bonus":{"method":"COMPOUND","paidUpParticipates":false,"surrenderBasis":"NONE"}}
            """.formatted(payoutSchedule);
    }

    @Test
    void aWithProfitsEndowmentIsPublishedOverTheWire() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProductOfCategory(tenantId, "WP-CONTRACT-01", "ENDOWMENT");
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(withProfitsVersion("[{\"kind\":\"MATURITY\",\"amountBasis\":\"PERCENT_OF_SA\",\"amountValue\":100}]")))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aWithProfitsTermProductIsRefusedInTheValidatorsWords() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProductOfCategory(tenantId, "WP-CONTRACT-02", "TERM_LIFE");
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(withProfitsVersion("[]")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("A TERM_LIFE product cannot be with-profits"));
    }

    // ---- Step 5: an annuity over the wire ----

    private static final String ANNUITY_RATES = """
        [{"age":60,"annualRatePerMille":72},{"age":61,"annualRatePerMille":74},{"age":62,"annualRatePerMille":76}]""";

    private static String annuityVersion(String rates) {
        return """
            {"ifrsMeasurementModel":"GMM","effectiveDate":"2026-01-01",
             "payoutTerms":{"freeLookDays":15},"tiraFiling":{"reference":"TIRA/CONTRACT/ANN","approvalDate":"2026-01-15"},
             "eligibility":{"minEntryAge":60,"maxEntryAge":62},
             "ratingTable":[{"factorType":"AGE","band":"60-62","multiplier":1.0,"ageFrom":60,"ageTo":62},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
             "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}],
             "annuity":{"timing":"ARREARS","proofOfLifeIntervalMonths":12,"basisReference":"ACT/ANN/2026","basisDate":"2026-01-01",
               "forms":[{"formCode":"LIFE-10G","guaranteeYears":10,"joint":false,"escalationPercent":3,
                         "capitalProtected":false,"rateBasis":"UNISEX","rates":%s}],
               "frequencies":[{"frequency":"MONTHLY","factor":0.98},{"frequency":"ANNUAL","factor":1}]}}
            """.formatted(rates);
    }

    private UUID activeVersion(UUID tenantId, UUID productId) throws Exception {
        MvcResult snapshot = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(snapshot.getResponse().getContentAsString())
            .path("productVersionId").traverse(objectMapper).readValueAs(UUID.class);
    }

    @Test
    void anAnnuityIsPublishedAndItsFormsReadBackToSpec() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProductOfCategory(tenantId, "ANN-CONTRACT-01", "ANNUITY");
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(annuityVersion(ANNUITY_RATES)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        UUID versionId = activeVersion(tenantId, productId);
        mockMvc.perform(get("/products/" + productId + "/versions/" + versionId + "/annuity")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.timing").value("ARREARS"))
            .andExpect(jsonPath("$.proofOfLifeIntervalMonths").value(12))
            .andExpect(jsonPath("$.forms[0].formCode").value("LIFE-10G"))
            .andExpect(jsonPath("$.forms[0].guaranteeYears").value(10))
            .andExpect(jsonPath("$.forms[0].joint").value(false))
            .andExpect(jsonPath("$.forms[0].escalationPercent").value("3"))
            .andExpect(jsonPath("$.forms[0].capitalProtected").value(false))
            .andExpect(jsonPath("$.forms[0].rateBasis").value("UNISEX"))
            .andExpect(jsonPath("$.forms[0].rates").doesNotExist())
            .andExpect(jsonPath("$.frequencies[0].frequency").exists())
            .andExpect(jsonPath("$.frequencies[?(@.frequency=='MONTHLY')].factor").value("0.98"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aGapInAnAnnuityGridIsRefusedNamingTheAge() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProductOfCategory(tenantId, "ANN-CONTRACT-02", "ANNUITY");
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(annuityVersion("[{\"age\":60,\"annualRatePerMille\":72},{\"age\":62,\"annualRatePerMille\":76}]")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("Form LIFE-10G has no rate for age 61"));
    }

    @Test
    void theAnnuityReadOfAnOrdinaryVersionIsA404WithItsOwnCode() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createProductOfCategory(tenantId, "ANN-CONTRACT-03", "ENDOWMENT");
        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(withProfitsVersion("[{\"kind\":\"MATURITY\",\"amountBasis\":\"PERCENT_OF_SA\",\"amountValue\":100}]")))
            .andExpect(status().isCreated());
        UUID versionId = activeVersion(tenantId, productId);
        mockMvc.perform(get("/products/" + productId + "/versions/" + versionId + "/annuity")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_AN_ANNUITY"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

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
