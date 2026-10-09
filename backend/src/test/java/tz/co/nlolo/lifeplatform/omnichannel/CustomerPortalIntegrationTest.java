package tz.co.nlolo.lifeplatform.omnichannel;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer portal's dashboard and policy page (2026-10-08, the customer portal design step 2): a policyholder reads
 * their own business, every figure from the module that owns it, and nobody else's -- a different customer asking for
 * the policy is refused, and the dashboard can only ever be the token's own.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AccumulationTestFixtures.class)
class CustomerPortalIntegrationTest {

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
        // The savings stack, and claims: the dashboard counts claims in progress and the policy page lists them.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            java.util.stream.Stream.concat(java.util.Arrays.stream(DepositTestMigrations.ALL), java.util.stream.Stream.of(
                "db-migrations/claims/V1__create_claims_schema.sql",
                "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
                "db-migrations/claims/V3__registration_idempotency_key.sql",
                "db-migrations/claims/V4__rls_fail_closed.sql",
                "db-migrations/claims/V5__claim_policy_member.sql",
                "db-migrations/claims/V6__exclusion_decline.sql",
                "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
                "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
                "db-migrations/claims/V9__zero_annuity_settlement.sql",
                "db-migrations/claims/V10__funeral_claims.sql",
                "db-migrations/claims/V11__claim_document_request.sql",
                // Communication: the dashboard counts unread messages and the inbox reads them (step 7).
                "db-migrations/communication/V1__create_communication_schema.sql",
                "db-migrations/communication/V2__template_identity.sql",
                "db-migrations/communication/V3__seed_offer_templates.sql",
                "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
                "db-migrations/communication/V5__dispatch_claimed_status.sql",
                "db-migrations/communication/V6__grants_and_rls.sql",
                "db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql",
                "db-migrations/communication/V8__platform_default_templates.sql",
                "db-migrations/communication/V9__payment_received_template.sql",
                "db-migrations/communication/V10__account_statement_template.sql",
                "db-migrations/communication/V11__vesting_reminder_template.sql",
                "db-migrations/communication/V12__funeral_templates.sql",
                "db-migrations/communication/V13__unit_linked_templates.sql",
                "db-migrations/communication/V14__unit_linked_statement_template.sql",
                "db-migrations/communication/V15__dispatch_body_and_inbox.sql")).toArray(String[]::new));
    }

    @Autowired private tz.co.nlolo.lifeplatform.communication.api.NotificationApi notificationApi;

    /**
     * Step 7: what we sent the customer is in their inbox once, however many channels carried it, with the text as
     * sent; opening it marks it read and the dashboard's count falls. Another customer can neither see nor open it.
     */
    @Test
    void aCustomerReadsWhatWeSentThemOnceAndOpensIt() throws Exception {
        String policyNumber = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY).policyNumber();
        UUID holder = asTenant(() -> policyApi.getPolicy(policyNumber)).policyholderPartyId();
        asTenant(() -> { notificationApi.notify(UUID.randomUUID(), holder, policyNumber, "PAYMENT_RECEIVED",
            java.util.Map.of("amount", "TZS 50,000.00", "policyNumber", policyNumber)); return null; });

        String body = mockMvc.perform(customer(get("/customer/messages"), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString();
        // Its SMS and its email (when both are on file) are one message here.
        List<java.util.Map<String, Object>> payments =
            com.jayway.jsonpath.JsonPath.read(body, "$[?(@.title == 'Payment received')]");
        org.assertj.core.api.Assertions.assertThat(payments).hasSize(1);
        org.assertj.core.api.Assertions.assertThat((String) payments.get(0).get("body")).contains("TZS 50,000.00");
        org.assertj.core.api.Assertions.assertThat(payments.get(0).get("read")).isEqualTo(false);
        String messageId = (String) payments.get(0).get("messageId");
        int unread = com.jayway.jsonpath.JsonPath.<List<Object>>read(body, "$[?(@.read == false)]").size();

        UUID stranger = UUID.randomUUID();
        mockMvc.perform(customer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/customer/messages/" + messageId + "/read"), stranger))
            .andExpect(status().isNotFound());

        mockMvc.perform(customer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/customer/messages/" + messageId + "/read"), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.read").value(true));
        mockMvc.perform(customer(get("/customer/dashboard"), holder))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unreadMessages").value(unread - 1));
        mockMvc.perform(customer(get("/customer/messages"), stranger))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private MockMvc mockMvc;
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository claimRepository;
    @Autowired private tz.co.nlolo.lifeplatform.claims.api.ClaimJourneyApi journeyApi;

    private static <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private static MockHttpServletRequestBuilder customer(MockHttpServletRequestBuilder request, UUID partyId) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", partyId.toString())));
    }

    @Test
    void aPolicyholderSeesTheirDashboardAndPolicyAndAnotherCustomerIsRefused() throws Exception {
        String policyNumber = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY).policyNumber();
        UUID holder = asTenant(() -> policyApi.getPolicy(policyNumber)).policyholderPartyId();

        mockMvc.perform(customer(get("/customer/dashboard"), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.displayName").value(org.hamcrest.Matchers.startsWith("Savings Test Life")))
            .andExpect(jsonPath("$.policies.length()").value(1))
            .andExpect(jsonPath("$.policies[0].policyNumber").value(policyNumber))
            .andExpect(jsonPath("$.policies[0].productName").value("Savings Test Product"))
            // An offer awaiting its first premium: that premium is what is due next.
            .andExpect(jsonPath("$.nextPremium.policyNumber").value(policyNumber))
            .andExpect(jsonPath("$.claimsInProgress").value(0));

        mockMvc.perform(customer(get("/customer/policies/" + policyNumber), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.summary.policyNumber").value(policyNumber))
            .andExpect(jsonPath("$.lifeAssuredName").value(org.hamcrest.Matchers.startsWith("Savings Test Life")))
            .andExpect(jsonPath("$.coveredLives").doesNotExist())
            .andExpect(jsonPath("$.claims.length()").value(0));

        // Somebody else's customer token: the policy is not theirs, and their dashboard is empty.
        UUID stranger = UUID.randomUUID();
        mockMvc.perform(customer(get("/customer/policies/" + policyNumber), stranger))
            .andExpect(status().isForbidden());
    }

    @Autowired private tz.co.nlolo.lifeplatform.product.api.ProductApi productApi;
    @Autowired private tz.co.nlolo.lifeplatform.party.api.PartyApi partyApi;

    /**
     * Step 5: a customer sees only what is offered online, prices it on their own details, and asking for it opens one
     * application in their name -- a second ask while it is reviewed is refused, and another customer sees none of it.
     */
    @Test
    void aCustomerPricesAnOnlineProductAndAsksForItOnce() throws Exception {
        UUID productId = asTenant(() -> {
            var product = productApi.createProduct("TERM-ONLINE-" + UUID.randomUUID().toString().substring(0, 6),
                "Online Term Cover", tz.co.nlolo.lifeplatform.product.api.ProductCategory.TERM_LIFE, "TZS", "actuary");
            productApi.publishVersion(product.productId(), tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel.PAA,
                TODAY.minusDays(1), null,
                List.of(new tz.co.nlolo.lifeplatform.product.api.ProductApi.RatingFactorInput(
                    tz.co.nlolo.lifeplatform.product.api.FactorType.SUM_ASSURED_BAND, "ANY", java.math.BigDecimal.ONE, null, null,
                    java.math.BigDecimal.ZERO, new java.math.BigDecimal("100000000")),
                    new tz.co.nlolo.lifeplatform.product.api.ProductApi.RatingFactorInput(
                        tz.co.nlolo.lifeplatform.product.api.FactorType.OCCUPATION_CLASS, "CLASS_1", java.math.BigDecimal.ONE)),
                List.of(new tz.co.nlolo.lifeplatform.product.api.ProductApi.BenefitInput(
                    tz.co.nlolo.lifeplatform.product.api.BenefitType.DEATH,
                    tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod.SUM_ASSURED)),
                null,
                // A priced version must price every life it accepts: both sexes, every smoker status.
                java.util.Arrays.stream(tz.co.nlolo.lifeplatform.product.api.Sex.values())
                    .flatMap(sex -> java.util.Arrays.stream(tz.co.nlolo.lifeplatform.product.api.SmokerStatus.values())
                        .map(smoker -> new tz.co.nlolo.lifeplatform.product.api.ProductApi.BaseRateInput(18, 60, sex, smoker,
                            new java.math.BigDecimal("10.0000"))))
                    .toList(),
                new tz.co.nlolo.lifeplatform.product.api.EligibilityBounds(18, 60, null, null, null, null),
                tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING, "actuary");
            productApi.describeOnline(product.productId(), true, "Cover for your family if you die",
                List.of("Pays the sum assured on death", " "));
            return product.productId();
        });
        UUID me = asTenant(() -> partyApi.registerIndividual(new tz.co.nlolo.lifeplatform.party.api.IndividualRegistration(
            "Online Applicant", TODAY.minusYears(30).minusDays(10), "+255718999001", null,
            tz.co.nlolo.lifeplatform.party.api.Sex.MALE, null, tz.co.nlolo.lifeplatform.party.api.IdentityDocument.none(),
            null, "CLASS_1", null, null, tz.co.nlolo.lifeplatform.party.api.Address.none()), "test-agent").partyId());

        mockMvc.perform(customer(get("/customer/products"), me))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            // Only what is offered online: the savings products other tests publish are not.
            .andExpect(jsonPath("$[?(@.productName == 'Savings Test Product')]").isEmpty())
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].quotable").value(true))
            .andExpect(jsonPath("$[?(@.productId == '" + productId + "')].benefits[0]").value("Pays the sum assured on death"));

        // 1,000,000 at 10 per mille: 10,000 a year.
        mockMvc.perform(customer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/customer/products/" + productId + "/quote"), me)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"sumAssured\":1000000,\"frequency\":\"ANNUALLY\"}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.yearly").value(10000.0))
            .andExpect(jsonPath("$.ageAtEntry").value(30));

        String ask = "{\"productId\":\"" + productId + "\",\"sumAssured\":1000000,\"frequency\":\"MONTHLY\"}";
        mockMvc.perform(customer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/customer/applications"), me)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(ask))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.productName").value("Online Term Cover"))
            .andExpect(jsonPath("$.statusText").value("Being reviewed"));
        mockMvc.perform(customer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/customer/applications"), me)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(ask))
            .andExpect(status().isConflict());

        mockMvc.perform(customer(get("/customer/applications"), me))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(customer(get("/customer/applications"), UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // Taken offline: no longer the customer's to price.
        asTenant(() -> productApi.describeOnline(productId, false, null, List.of()));
        mockMvc.perform(customer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/customer/products/" + productId + "/quote"), me)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"sumAssured\":1000000}"))
            .andExpect(status().isNotFound());
    }

    /**
     * Step 4: a claimant sees their claim in plain words, with the document staff asked for as an action -- a withdrawn
     * request drops off, and the claim is nobody else's to read. A customer cannot file against a policy they do not hold.
     */
    @Test
    void aClaimantFollowsTheirClaimAndSeesTheDocumentAskedFor() throws Exception {
        String policyNumber = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY).policyNumber();
        UUID holder = asTenant(() -> policyApi.getPolicy(policyNumber)).policyholderPartyId();
        UUID claimId = asTenant(() -> claimRepository.save(new tz.co.nlolo.lifeplatform.claims.domain.Claim(TENANT,
            policyNumber, null, holder, tz.co.nlolo.lifeplatform.claims.api.ClaimType.MATURITY, TODAY.minusDays(1),
            new tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails(TODAY.minusDays(1)), "test-registrar", null))
            .getClaimId());
        asTenant(() -> journeyApi.requestDocument(claimId, "Certified copy of ID", "We could not read the copy sent",
            "assessor-sub", "Asha Assessor"));
        UUID withdrawn = asTenant(() -> journeyApi.requestDocument(claimId, "Bank statement", null, "assessor-sub",
            "Asha Assessor")).requestId();
        asTenant(() -> journeyApi.withdraw(claimId, withdrawn));

        mockMvc.perform(customer(get("/customer/claims"), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].claimId").value(claimId.toString()))
            .andExpect(jsonPath("$[0].statusText").value("Claim received"))
            .andExpect(jsonPath("$[0].actionsRequired").value(1));

        mockMvc.perform(customer(get("/customer/claims/" + claimId), holder))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.steps[0].label").value("Claim received"))
            .andExpect(jsonPath("$.steps[0].state").value("DONE"))
            .andExpect(jsonPath("$.steps[1].state").value("CURRENT"))
            .andExpect(jsonPath("$.steps[2].state").value("PENDING"))
            .andExpect(jsonPath("$.decision").doesNotExist())
            .andExpect(jsonPath("$.requests.length()").value(1))
            .andExpect(jsonPath("$.requests[0].document").value("Certified copy of ID"))
            .andExpect(jsonPath("$.requests[0].reason").value("We could not read the copy sent"))
            .andExpect(jsonPath("$.requests[0].status").value("OPEN"));

        UUID stranger = UUID.randomUUID();
        mockMvc.perform(customer(get("/customer/claims/" + claimId), stranger))
            .andExpect(status().isForbidden());
        mockMvc.perform(customer(get("/customer/claims"), stranger))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // Naming themselves as claimant does not let a customer file on somebody else's policy.
        mockMvc.perform(customer(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/claims"), stranger)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"policyNumber\":\"" + policyNumber + "\",\"claimantPartyId\":\"" + stranger
                    + "\",\"claimType\":\"MATURITY\",\"dateOfEvent\":\"" + TODAY.minusDays(1)
                    + "\",\"details\":{\"claimType\":\"MATURITY\",\"maturityDate\":\"" + TODAY.minusDays(1) + "\"}}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffAndAgentsDoNotUseTheCustomersOwnEndpoints() throws Exception {
        mockMvc.perform(get("/customer/dashboard").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                .jwt(b -> b.claim("tenant_id", TENANT.toString()))))
            .andExpect(status().isForbidden());
    }
}
