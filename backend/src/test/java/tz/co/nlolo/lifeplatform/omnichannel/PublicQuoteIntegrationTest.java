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

import static org.assertj.core.api.Assertions.assertThat;
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
@Import({AccumulationTestFixtures.class, FuneralTestFixtures.class, PublicQuoteIntegrationTest.Texts.class})
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

    // --- applying (2026-11-08, phase 2) -----------------------------------------------------------------------------

    /** The SMS gateway, ahead of the real one, so the code texted can be read back. */
    static class RecordingSms implements tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender {
        final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public boolean supports(tz.co.nlolo.lifeplatform.communication.api.NotificationChannel channel) {
            return channel == tz.co.nlolo.lifeplatform.communication.api.NotificationChannel.SMS;
        }
        @Override public SendResult send(String destination, String body) {
            sent.add(destination + "|" + body);
            return SendResult.ok();
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Texts {
        @org.springframework.context.annotation.Bean
        @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        RecordingSms recordingSms() { return new RecordingSms(); }
    }

    @Autowired private RecordingSms sms;
    @Autowired private tz.co.nlolo.lifeplatform.party.api.PartyApi partyApi;
    @Autowired private tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi underwritingApi;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String codeTextedTo(String phone) {
        String last = sms.sent.stream().filter(m -> m.startsWith(phone + "|")).reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no code texted to " + phone));
        // After the "|": the phone number before it has digits of its own.
        return last.substring(last.indexOf('|') + 1).replaceAll("(?s).*?(\\d{6}).*", "$1");
    }

    private void sendCode(String visit, String typedPhone, int expectedStatus) throws Exception {
        mockMvc.perform(post("/public/phone-codes").header("X-Visit", visit).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + typedPhone + "\",\"language\":\"EN\"}"))
            .andExpect(status().is(expectedStatus));
    }

    private org.springframework.test.web.servlet.ResultActions check(String visit, String typedPhone, String code)
            throws Exception {
        return mockMvc.perform(post("/public/phone-codes/check").header("X-Visit", visit)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\"" + typedPhone + "\",\"code\":\"" + code + "\",\"language\":\"EN\"}"));
    }

    /** Sends a code, reads it off the phone, and answers with the proof an application carries. */
    private String confirm(String visit, String typedPhone, String phone) throws Exception {
        mockMvc.perform(post("/public/phone-codes").header("X-Visit", visit).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + typedPhone + "\",\"language\":\"EN\"}"))
            .andExpect(status().isNoContent())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        String body = check(visit, typedPhone, codeTextedTo(phone))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.phoneNumber").value(phone))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("phoneToken").asText();
    }

    private String consentVersion() throws Exception {
        String body = mockMvc.perform(get("/public/consent"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("version").asText();
    }

    private String application(String phoneToken, String name, String dob, UUID productId) throws Exception {
        return "{\"phoneToken\":\"" + phoneToken + "\",\"applicant\":{\"fullName\":\"" + name + "\",\"dateOfBirth\":\""
            + dob + "\",\"sex\":\"FEMALE\",\"smokerStatus\":\"NON_SMOKER\"},\"consent\":{\"version\":\"" + consentVersion()
            + "\",\"agreed\":true},\"request\":{\"productId\":\"" + productId + "\",\"amount\":1000000,\"termMonths\":12,"
            + "\"beneficiaries\":[{\"name\":\"Juma Ali\",\"relationship\":\"Brother\",\"sharePercent\":100}],"
            + "\"language\":\"EN\"}}";
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor underwriter() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_UNDERWRITER"))
            .jwt(b -> b.subject("underwriter").claim("tenant_id", TENANT.toString()));
    }

    /**
     * 2026-11-02, the user: the phone is confirmed by a texted code; the visitor becomes a KYC-pending client with a
     * case "Online (public)", and what they agreed to is kept word for word.
     */
    @Test
    void aVisitorConfirmsTheirPhoneByCodeAndAppliesAsANewClient() throws Exception {
        UUID productId = onlineDeposit();
        String visit = visit();
        String token = confirm(visit, "0713 555 101", "+255713555101");
        assertThat(sms.sent).anyMatch(m -> m.startsWith("+255713555101|Your application code is "));

        String body = mockMvc.perform(post("/public/applications").header("X-Visit", visit)
                .contentType(MediaType.APPLICATION_JSON).content(application(token, "Neema  Mushi", "1991-05-20", productId)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.statusText").value("Being reviewed"))
            .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(objectMapper.readTree(body).path("caseId").asText());

        var opened = asTenant(() -> underwritingApi.getCase(caseId));
        assertThat(opened.source()).isEqualTo(tz.co.nlolo.lifeplatform.underwriting.api.CaseSource.PUBLIC_ONLINE);
        assertThat(opened.sumAssuredAmount()).isEqualByComparingTo("1000000");
        var client = asTenant(() -> partyApi.getPartyDetail(opened.applicantPartyId()));
        assertThat(client.displayName()).isEqualTo("Neema Mushi");
        assertThat(client.phoneNumber()).isEqualTo("+255713555101");
        assertThat(client.kycStatus()).isEqualTo(tz.co.nlolo.lifeplatform.party.api.KycStatus.PENDING);
        assertThat(client.preferredLanguage()).isEqualTo("en");

        mockMvc.perform(get("/underwriting/cases/" + caseId + "/public-application").with(underwriter()))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.phoneNumber").value("+255713555101"))
            .andExpect(jsonPath("$.consentVersion").value(consentVersion()))
            .andExpect(jsonPath("$.consentText").value(org.hamcrest.Matchers.startsWith("Before you send this application")))
            .andExpect(jsonPath("$.possibleExistingClients.length()").value(0));

        // One at a time: the same phone asking again for the same cover while it is reviewed is refused.
        String again = confirm(visit, "+255713555101", "+255713555101");
        mockMvc.perform(post("/public/applications").header("X-Visit", visit)
                .contentType(MediaType.APPLICATION_JSON).content(application(again, "Neema Mushi", "1991-05-20", productId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("ALREADY_REQUESTED"));
    }

    /** Never merged (the user's decision): somebody already on the books is a flag for staff, and a new client. */
    @Test
    void someoneWhoMayAlreadyBeAClientIsFlaggedNeverMerged() throws Exception {
        UUID productId = onlineDeposit();
        UUID existing = asTenant(() -> partyApi.registerIndividual("Rehema Said", java.time.LocalDate.of(1985, 2, 3),
            "+255713555202", null, "staff").partyId());
        String visit = visit();
        String token = confirm(visit, "0713555202", "+255713555202");

        String body = mockMvc.perform(post("/public/applications").header("X-Visit", visit)
                .contentType(MediaType.APPLICATION_JSON).content(application(token, "Rehema Said", "1985-02-03", productId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(objectMapper.readTree(body).path("caseId").asText());

        assertThat(asTenant(() -> underwritingApi.getCase(caseId)).applicantPartyId()).isNotEqualTo(existing);
        mockMvc.perform(get("/underwriting/cases/" + caseId + "/public-application").with(underwriter()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.possibleExistingClients.length()").value(1))
            .andExpect(jsonPath("$.possibleExistingClients[0].partyId").value(existing.toString()))
            .andExpect(jsonPath("$.possibleExistingClients[0].displayName").value("Rehema Said"));
    }

    /** Ten minutes, used once, five tries, three texts an hour -- and a code works only in the visit that asked. */
    @Test
    void aWrongCodeCostsATryTheFifthEndsItAndTextsAreCapped() throws Exception {
        String visit = visit();
        sendCode(visit, "0713555303", 204);
        String first = codeTextedTo("+255713555303");
        // Another visit cannot use it.
        check(visit(), "0713555303", first).andExpect(status().isUnprocessableEntity());

        sendCode(visit, "0713555303", 204);
        String fresh = codeTextedTo("+255713555303");
        String bad = fresh.equals("000000") ? "111111" : "000000";
        for (int left = 4; left >= 1; left--) {
            check(visit, "0713555303", bad)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                    left + (left == 1 ? " try left" : " tries left"))));
        }
        check(visit, "0713555303", bad)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no tries left")));
        // Spent: even the right code no longer works.
        check(visit, "0713555303", fresh).andExpect(status().isUnprocessableEntity());

        // The third text in the hour is the last; a fourth is refused.
        sendCode(visit, "0713555303", 204);
        mockMvc.perform(post("/public/phone-codes").header("X-Visit", visit).contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"0713555303\",\"language\":\"EN\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("three codes")));
    }

    /** The phone is the one confirmed in THIS visit, and the declaration must be agreed in its current words. */
    @Test
    void applyingNeedsThePhoneConfirmedInThisVisitAndTheDeclarationAgreed() throws Exception {
        UUID productId = onlineDeposit();
        String visit = visit();
        String token = confirm(visit, "0713555404", "+255713555404");

        mockMvc.perform(post("/public/applications").header("X-Visit", visit()).contentType(MediaType.APPLICATION_JSON)
                .content(application(token, "Zawadi Ally", "1993-01-01", productId)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/public/applications").header("X-Visit", visit).contentType(MediaType.APPLICATION_JSON)
                .content(application(token, "Zawadi Ally", "1993-01-01", productId)
                    .replace("\"agreed\":true", "\"agreed\":false")))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/public/applications").header("X-Visit", visit).contentType(MediaType.APPLICATION_JSON)
                .content(application(token, "Zawadi Ally", "1993-01-01", productId).replace(consentVersion(), "OLD-WORDING")))
            .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM omnichannel.public_application WHERE phone_number = ?",
            Integer.class, "+255713555404")).isZero();
    }
}
