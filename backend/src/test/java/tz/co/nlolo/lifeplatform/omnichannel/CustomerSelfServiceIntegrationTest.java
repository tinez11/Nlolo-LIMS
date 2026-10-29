package tz.co.nlolo.lifeplatform.omnichannel;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.AccumulationTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestMigrations;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.PortfolioCode;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The self-service rework (2026-10-29): a product is priced by what it is -- a deposit from its grid, a funeral plan for
 * the family -- and an application carries who receives the money and the health answers, straight to underwriting.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({AccumulationTestFixtures.class, FuneralTestFixtures.class})
class CustomerSelfServiceIntegrationTest {

    private static final String SPEC = "api/openapi/openapi-omnichannel.yaml";

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
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductApi productApi;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private AccumulationTestFixtures deposits;
    @Autowired private FuneralTestFixtures funerals;
    @Autowired private tz.co.nlolo.lifeplatform.policy.api.PolicyApi policyApi;

    private static <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private static MockHttpServletRequestBuilder customer(MockHttpServletRequestBuilder request, UUID partyId) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", partyId.toString())));
    }

    /** A deposit filed as an endowment is a deposit: priced from its grid, never as cover, and asked for once. */
    @Test
    void aDepositIsPricedFromItsGridAndAskedForWithNoHealthQuestions() throws Exception {
        UUID productId = asTenant(() -> {
            var product = productApi.createProduct("FTD-ONLINE-" + UUID.randomUUID().toString().substring(0, 6),
                "Online Deposit", ProductCategory.ENDOWMENT, PortfolioCode.DEP, "TZS", "actuary");
            deposits.publishDepositVersion(TENANT, product.productId(), AccumulationTestFixtures.USER_GRID);
            productApi.describeOnline(product.productId(), true, "Lock money in and earn more", List.of());
            return product.productId();
        });
        UUID me = funerals.person(TENANT, 40, Sex.MALE);

        mockMvc.perform(customer(get("/customer/products"), me))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].kind").value("DEPOSIT"))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].online").value(true))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].terms.depositTermMonths[2]").value(12))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].questions.length()").value(0));

        // 1,000,000 for 12 months on the first band: 5% for the term.
        mockMvc.perform(customer(post("/customer/products/" + productId + "/quote"), me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000000,\"termMonths\":12}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.kind").value("DEPOSIT"))
            .andExpect(jsonPath("$.deposit.ratePercent").value(5.0))
            .andExpect(jsonPath("$.deposit.maturityValue").value(1050000.0))
            .andExpect(jsonPath("$.deposit.offeredTermMonths.length()").value(3));

        mockMvc.perform(customer(post("/customer/applications"), me)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"amount\":1000000,\"termMonths\":12,"
                    + "\"beneficiaries\":[{\"name\":\"Asha Juma\",\"relationship\":\"Daughter\",\"sharePercent\":100}]}"))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.statusText").value("Being reviewed"))
            .andExpect(jsonPath("$.stage").value("REVIEWING"));

        UnderwritingCaseView opened = asTenant(() -> underwritingApi.listCases(null, me, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0));
        assertThat(opened.sumAssuredAmount()).isEqualByComparingTo("1000000");
        assertThat(opened.premiumFrequency()).isEqualTo("SINGLE");
        assertThat(opened.requestedTermMonths()).isEqualTo(12);
        assertThat(opened.source()).isEqualTo(tz.co.nlolo.lifeplatform.underwriting.api.CaseSource.CUSTOMER_ONLINE);
    }

    /**
     * A savings account (2026-10-29, the user): opened with one deposit, at least the minimum balance, priced at the rate
     * it earns -- and, once accepted, billed exactly that deposit once. Before this an account issued from a case was
     * billed as cover: sum assured x a base rate.
     */
    @Test
    void aSavingsAccountOpensWithOneDepositAndIsBilledExactlyThat() throws Exception {
        UUID productId = asTenant(() -> {
            var product = productApi.createProduct("SAVE-ONLINE-" + UUID.randomUUID().toString().substring(0, 6),
                "Online Savings", ProductCategory.ENDOWMENT, PortfolioCode.SAV, "TZS", "actuary");
            productApi.publishVersion(product.productId(), tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel.GMM,
                TODAY.minusDays(1), null,
                List.of(new ProductApi.RatingFactorInput(tz.co.nlolo.lifeplatform.product.api.FactorType.AGE, "18-70",
                        BigDecimal.ONE, 18, 70),
                    new ProductApi.RatingFactorInput(tz.co.nlolo.lifeplatform.product.api.FactorType.SUM_ASSURED_BAND,
                        "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(tz.co.nlolo.lifeplatform.product.api.BenefitType.DEATH,
                    tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), tz.co.nlolo.lifeplatform.product.api.EligibilityBounds.none(),
                tz.co.nlolo.lifeplatform.product.api.FrequencyLoading.none(), tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING,
                tz.co.nlolo.lifeplatform.product.api.CashValuePlan.none(),
                tz.co.nlolo.lifeplatform.product.api.PayoutPlan.authored(
                    new tz.co.nlolo.lifeplatform.product.api.PayoutTerms(15, null, null, null),
                    List.of(new tz.co.nlolo.lifeplatform.product.api.PayoutRowInput(
                        tz.co.nlolo.lifeplatform.product.api.PayoutKind.MATURITY, null, null,
                        tz.co.nlolo.lifeplatform.product.api.PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal("100"), null))),
                AccumulationTestFixtures.SAVINGS, "actuary");
            productApi.describeOnline(product.productId(), true, "Put money aside and earn interest", List.of());
            return product.productId();
        });
        UUID me = funerals.person(TENANT, 33, Sex.FEMALE);

        mockMvc.perform(customer(get("/customer/products"), me))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].kind").value("SAVINGS_ACCOUNT"))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].online").value(true))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].terms.minAmount").value(50000.0));

        // Below the minimum balance: refused in the customer's words.
        mockMvc.perform(customer(post("/customer/products/" + productId + "/quote"), me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":40000}"))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(customer(post("/customer/products/" + productId + "/quote"), me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100000}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.kind").value("SAVINGS_ACCOUNT"))
            .andExpect(jsonPath("$.frequency").value("SINGLE"))
            .andExpect(jsonPath("$.savings.ratePercent").value(3.0))
            .andExpect(jsonPath("$.savings.minimumBalance").value(50000.0));

        mockMvc.perform(customer(post("/customer/applications"), me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"" + productId + "\",\"amount\":100000}"))
            .andExpect(status().isCreated());
        UUID caseId = asTenant(() -> underwritingApi.listCases(null, me, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).caseId());
        asTenant(() -> underwritingApi.submitAssessment(caseId, tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType.FINANCIAL,
            "Source of funds seen", new BigDecimal("10"), "assessor-one"));
        asTenant(() -> underwritingApi.decide(caseId, new UnderwritingApi.DecisionInput(
            tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome.ACCEPT, null, "Savings, no risk to assess", true),
            "senior-two", true));

        var policy = asTenant(() -> policyApi.searchPolicies(me, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0));
        assertThat(policy.premiumAmount()).isEqualByComparingTo("100000");
        assertThat(policy.premiumFrequency()).isEqualTo("SINGLE");
    }

    /**
     * Cover published with no base rates refuses every quote (Nlolo Money-Back 20, 2026-10-29): it is offered through an
     * adviser rather than letting a customer answer every question to learn that, and asking about it needs no answers.
     */
    @Test
    void coverWithNoRatesIsAskedAboutThroughAnAdviser() throws Exception {
        UUID productId = asTenant(() -> {
            var product = productApi.createProduct("TERM-NORATE-" + UUID.randomUUID().toString().substring(0, 6),
                "Unpriced Term", ProductCategory.TERM_LIFE, "TZS", "actuary");
            productApi.publishVersion(product.productId(), tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel.PAA,
                TODAY.minusDays(1), null,
                List.of(new ProductApi.RatingFactorInput(tz.co.nlolo.lifeplatform.product.api.FactorType.AGE, "18-60",
                        BigDecimal.ONE, 18, 60),
                    new ProductApi.RatingFactorInput(tz.co.nlolo.lifeplatform.product.api.FactorType.SUM_ASSURED_BAND,
                        "ANY", BigDecimal.ONE, null, null, BigDecimal.ZERO, new BigDecimal("100000000"))),
                List.of(new ProductApi.BenefitInput(tz.co.nlolo.lifeplatform.product.api.BenefitType.DEATH,
                    tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), new tz.co.nlolo.lifeplatform.product.api.EligibilityBounds(18, 60, null, null, null, null),
                tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING, "actuary");
            productApi.describeOnline(product.productId(), true, "Cover for your family", List.of());
            return product.productId();
        });
        UUID me = funerals.person(TENANT, 35, Sex.MALE);

        mockMvc.perform(customer(get("/customer/products"), me))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].online").value(false))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].questions.length()").value(0));
        mockMvc.perform(customer(post("/customer/products/" + productId + "/quote"), me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"sumAssured\":1000000}"))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(customer(post("/customer/applications"), me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"" + productId + "\"}"))
            .andExpect(status().isCreated());

        // The underwriter sees it as a call back -- no amount, call the customer -- and can list just those.
        mockMvc.perform(get("/underwriting/cases").param("source", "CALL_BACK").param("applicantPartyId", me.toString())
                .with(jwt().jwt(b -> b.claim("tenant_id", TENANT.toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid("api/openapi/openapi-underwriting.yaml"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].source").value("CALL_BACK"));
        mockMvc.perform(get("/underwriting/cases").param("source", "CUSTOMER_ONLINE").param("applicantPartyId", me.toString())
                .with(jwt().jwt(b -> b.claim("tenant_id", TENANT.toString()))
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    /**
     * A funeral plan is priced for the customer as main member and the family beside them; the application records the
     * family, the beneficiary and the two health questions as asked -- in Swahili when that is what was read.
     */
    @Test
    void aFamilyIsPricedAndItsApplicationCarriesTheFamilyAndTheAnswersAsAsked() throws Exception {
        var funeral = funerals.publishFamilia(TENANT);
        asTenant(() -> { productApi.describeOnline(funeral.productId(), true, "Pays for a funeral, fast", List.of()); return null; });
        UUID me = funerals.person(TENANT, 40, Sex.FEMALE);
        String family = "[{\"role\":\"SPOUSE\",\"name\":\"Juma Ali\",\"dateOfBirth\":\"" + TODAY.minusYears(42) + "\"},"
            + "{\"role\":\"CHILD\",\"name\":\"Neema Ali\",\"dateOfBirth\":\"" + TODAY.minusYears(6) + "\"}]";

        mockMvc.perform(customer(get("/customer/products"), me))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$[?(@.productId == '" + funeral.productId() + "')].kind").value("FUNERAL"))
            .andExpect(jsonPath("$[?(@.productId == '" + funeral.productId() + "')].terms.funeralPlans.length()").value(2))
            .andExpect(jsonPath("$[?(@.productId == '" + funeral.productId() + "')].questions.length()").value(2));

        mockMvc.perform(customer(post("/customer/products/" + funeral.productId() + "/quote"), me)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"B\",\"frequency\":\"MONTHLY\",\"family\":" + family + "}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.kind").value("FUNERAL"))
            .andExpect(jsonPath("$.funeral.length()").value(3))
            .andExpect(jsonPath("$.funeral[0].role").value("MAIN_MEMBER"))
            .andExpect(jsonPath("$.funeral[0].age").value(40));

        String body = "{\"productId\":\"" + funeral.productId() + "\",\"planCode\":\"B\",\"frequency\":\"MONTHLY\","
            + "\"family\":" + family + ",\"language\":\"SW\","
            + "\"beneficiaries\":[{\"name\":\"Juma Ali\",\"relationship\":\"Husband\",\"sharePercent\":100}],"
            + "\"answers\":[{\"code\":\"GOOD_HEALTH\",\"answer\":\"YES\"},%s]}";
        // A worrying answer needs a few words.
        mockMvc.perform(customer(post("/customer/applications"), me)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("{\"code\":\"SERIOUS_ILLNESS_5Y\",\"answer\":\"YES\"}")))
            .andExpect(status().isUnprocessableEntity());
        // Every question answered, then.
        mockMvc.perform(customer(post("/customer/applications"), me)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("{\"code\":\"SERIOUS_ILLNESS_5Y\",\"answer\":\"NO\"}")))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));

        UnderwritingCaseView opened = asTenant(() -> underwritingApi.listCases(null, me, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0));
        assertThat(opened.sumAssuredAmount()).isEqualByComparingTo(new BigDecimal("2000000"));
        var application = asTenant(() -> underwritingApi.funeralApplication(opened.caseId())).orElseThrow();
        assertThat(application.planCode()).isEqualTo("B");
        assertThat(application.dependants()).hasSize(2);
        var answers = asTenant(() -> underwritingApi.listDisclosures(opened.caseId())).get(0).answers();
        assertThat(answers).hasSize(2);
        assertThat(answers.get(0).question()).isEqualTo("Je, una afya njema na unaweza kufanya kazi zako za kawaida?");
        assertThat(answers.get(1).answer()).isEqualTo("NO");
    }
}
