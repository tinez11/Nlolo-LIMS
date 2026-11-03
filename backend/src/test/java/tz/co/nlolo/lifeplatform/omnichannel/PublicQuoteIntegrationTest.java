package tz.co.nlolo.lifeplatform.omnichannel;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.AccumulationTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestMigrations;
import tz.co.nlolo.lifeplatform.product.api.PortfolioCode;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public quote link (2026-11-02): anyone, with no login, sees the products sold online and a price on the facts
 * they type -- and nothing more. Turnstile is switched off here (it needs Cloudflare); PublicAccessTest covers its
 * refusal.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({AccumulationTestFixtures.class, FuneralTestFixtures.class})
class PublicQuoteIntegrationTest {

    private static final String SPEC = "api/openapi/openapi-omnichannel.yaml";
    private static final UUID TENANT = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.public-quote.enabled", () -> "true");
        registry.add("app.public-quote.tenant-id", TENANT::toString);
        registry.add("app.public-quote.turnstile.enabled", () -> "false");
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            FuneralTestMigrations.ALL);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductApi productApi;
    @Autowired private AccumulationTestFixtures deposits;
    @Autowired private ObjectMapper objectMapper;

    private static <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private UUID onlineDeposit() {
        return asTenant(() -> {
            var product = productApi.createProduct("PUB-DEP-" + UUID.randomUUID().toString().substring(0, 6),
                "Public Deposit", ProductCategory.ENDOWMENT, PortfolioCode.DEP, "TZS", "actuary");
            deposits.publishDepositVersion(TENANT, product.productId(), AccumulationTestFixtures.USER_GRID);
            productApi.describeOnline(product.productId(), true, "Lock money in and earn more", List.of());
            return product.productId();
        });
    }

    private String visit() throws Exception {
        String body = mockMvc.perform(post("/public/visits").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("visitToken").asText();
    }

    private static final String DEPOSIT_QUOTE = "{\"person\":{\"fullName\":\"Asha Juma\",\"dateOfBirth\":\"1990-04-12\","
        + "\"sex\":\"FEMALE\",\"smokerStatus\":\"NON_SMOKER\"},\"request\":{\"amount\":1000000,\"termMonths\":12}}";

    @Test
    void anyoneSeesTheProductsAndAPriceWithNoLogin() throws Exception {
        UUID productId = onlineDeposit();

        mockMvc.perform(get("/public/products"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].online").value(true));

        // 1,000,000 for 12 months on the first band: 5% for the term.
        mockMvc.perform(post("/public/products/" + productId + "/quote").header("X-Visit", visit())
                .contentType(MediaType.APPLICATION_JSON).content(DEPOSIT_QUOTE))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.kind").value("DEPOSIT"))
            .andExpect(jsonPath("$.deposit.maturityValue").value(1050000.0));
    }

    @Test
    void pricingNeedsAVisitAndARealOne() throws Exception {
        UUID productId = onlineDeposit();

        mockMvc.perform(post("/public/products/" + productId + "/quote")
                .contentType(MediaType.APPLICATION_JSON).content(DEPOSIT_QUOTE))
            .andExpect(status().isUnauthorized());

        String forged = visit().replaceAll(".$", "x");
        mockMvc.perform(post("/public/products/" + productId + "/quote").header("X-Visit", forged)
                .contentType(MediaType.APPLICATION_JSON).content(DEPOSIT_QUOTE))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void aVisitorMustGiveTheirDateOfBirth() throws Exception {
        UUID productId = onlineDeposit();

        mockMvc.perform(post("/public/products/" + productId + "/quote").header("X-Visit", visit())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"person\":{\"fullName\":\"Asha\"},\"request\":{\"amount\":1000000,\"termMonths\":12}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("Give your date of birth"));
    }

    @Test
    void aProductNotSoldOnlineIsNotPricedHere() throws Exception {
        mockMvc.perform(post("/public/products/" + UUID.randomUUID() + "/quote").header("X-Visit", visit())
                .contentType(MediaType.APPLICATION_JSON).content(DEPOSIT_QUOTE))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                if (code != 404 && code != 422) {
                    throw new AssertionError("expected 404 or 422 for an unknown product, got " + code);
                }
            });
    }

    @Test
    void theOtherEndpointsStayClosed() throws Exception {
        // Only the three public paths are open: a neighbour under /customer still needs a login.
        mockMvc.perform(get("/customer/products")).andExpect(status().isUnauthorized());
    }
}
