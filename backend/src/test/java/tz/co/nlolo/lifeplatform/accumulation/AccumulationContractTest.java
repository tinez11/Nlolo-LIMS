package tz.co.nlolo.lifeplatform.accumulation;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The accumulation surface against its own OpenAPI spec, over the wire. Strict validation, so a response
 * that drifts from the spec fails here rather than in a console typecheck weeks later.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AccumulationTestFixtures.class)
class AccumulationContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-accumulation.yaml";

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
            // The deposit list: issuance to SINGLE (policy V18), billing, the rail and V3.
            DepositTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private AccumulationTestFixtures fixtures;

    private static RequestPostProcessor staff(String role, String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(builder -> builder.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    @Test
    void aRateIsProposedApprovedAndListedToSpec() throws Exception {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        String body = "{\"ratePercent\": 6.5, \"effectiveFrom\": \"" + LocalDate.now().plusMonths(1) + "\"}";
        String created = mockMvc.perform(post("/products/" + product + "/rate-declarations").header("Idempotency-Key", key())
                .with(staff("ADMIN", "admin-one")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.declarationId");

        mockMvc.perform(post("/rate-declarations/" + id + "/approve").with(staff("FINANCE_OFFICER", "finance-two")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        mockMvc.perform(get("/products/" + product + "/rate-declarations").with(staff("FINANCE_OFFICER", "finance-two")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ratePercent").value("6.5"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void theProposerApprovingIsA422InTheServersWords() throws Exception {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        String body = "{\"ratePercent\": 6, \"effectiveFrom\": \"" + LocalDate.now().plusMonths(1) + "\"}";
        String id = JsonPath.read(mockMvc.perform(post("/products/" + product + "/rate-declarations").header("Idempotency-Key", key())
                .with(staff("ADMIN", "admin-one")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse().getContentAsString(), "$.declarationId");
        mockMvc.perform(post("/rate-declarations/" + id + "/approve").with(staff("ADMIN", "admin-one")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("ACCUMULATION_REFUSED"))
            .andExpect(jsonPath("$.detail").value("A declared rate must be approved by someone other than the person who proposed it"));
    }

    @Test
    void anUnderwriterCannotProposeOrApprove() throws Exception {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        // A body that WOULD succeed for an admin, so the 403 is the role gate and nothing else.
        String body = "{\"ratePercent\": 6, \"effectiveFrom\": \"" + LocalDate.now().plusMonths(1) + "\"}";
        mockMvc.perform(post("/products/" + product + "/rate-declarations").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    // ---- The account and money moving on it (task 6) -------------------------------------------

    /** 200,000 in, 5% allocation: 190,000 in the account. */
    private String fundedAccount() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("200000.00"), LocalDate.now());
        return issued.policyNumber();
    }

    @Test
    void theAccountIsReadToSpec() throws Exception {
        String policy = fundedAccount();
        mockMvc.perform(get("/policies/" + policy + "/account").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance.amount").value("190000.00"))
            .andExpect(jsonPath("$.entries[0].type").value("CONTRIBUTION"))
            .andExpect(jsonPath("$.entries[1].amount.amount").value("-10000.00"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aScalePolicyHasNoAccount() throws Exception {
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now());
        mockMvc.perform(get("/policies/" + scale.policyNumber() + "/account").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void aWithdrawalIsRequestedAndApprovedToSpec() throws Exception {
        String policy = fundedAccount();
        String created = mockMvc.perform(post("/policies/" + policy + "/account/withdrawals").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "staff-one")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"40000.00\",\"payeeRef\":\"+255700000001\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.withdrawalId");

        // REQUESTED from the rail, not paid: 202, and the response says APPROVED, never PAID.
        mockMvc.perform(post("/account-withdrawals/" + id + "/approve").with(staff("FINANCE_OFFICER", "finance-two")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void theClosingQuoteIsReadToSpec() throws Exception {
        String policy = fundedAccount();
        mockMvc.perform(get("/policies/" + policy + "/account/quote").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isOk())
            // Funded today, so no interest has accrued yet and the value is the balance.
            .andExpect(jsonPath("$.balance.amount").value("190000.00"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    /**
     * The computed statement and the (empty) filed list, to spec. Filing a PDF is NOT exercised here:
     * this class has no MinIO of its own, and an upload would silently use whatever object store the
     * dev stack runs. StatementIntegrationTest owns that path, with its own container.
     */
    @Test
    void theComputedStatementIsReadToSpec() throws Exception {
        String policy = fundedAccount();
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/policies/" + policy + "/account/statement").param("from", today).param("to", today)
                .with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openingBalance.amount").value("0.00"))
            .andExpect(jsonPath("$.closingBalance.amount").value("190000.00"))
            .andExpect(jsonPath("$.groups[0].type").value("CONTRIBUTION"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
        mockMvc.perform(get("/policies/" + policy + "/account/statements").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aMalformedAmountIsA400ThatNamesTheField() throws Exception {
        String policy = fundedAccount();
        mockMvc.perform(post("/policies/" + policy + "/account/withdrawals").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "staff-one")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"12.345\",\"payeeRef\":\"+255700000001\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("amount"));
    }

    // ---- Once per Idempotency-Key (fix after the 2026-10-02 live check) -------------------------
    //
    // A live check sent one top-up twice with one key -- the console's retry after a timeout -- and
    // it was collected and credited twice. A transfer in is the sharpest form of the same bug: it
    // posts to the ledger at once, with no payment rail between the retry and the money.

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void theSameTransferInSentTwiceCreditsOnce() throws Exception {
        String policy = fundedAccount();
        String key = key();
        String body = "{\"amount\":\"30000.00\",\"sourceScheme\":\"NSSF\"}";
        String first = mockMvc.perform(post("/policies/" + policy + "/account/transfers-in").header("Idempotency-Key", key)
                .with(staff("FINANCE_OFFICER", "finance-one")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/policies/" + policy + "/account/transfers-in").header("Idempotency-Key", key)
                .with(staff("FINANCE_OFFICER", "finance-one")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        // The same transfer, answered twice -- not a second one.
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(second, "$.transferId"))
            .isEqualTo(JsonPath.read(first, "$.transferId"));
        mockMvc.perform(get("/policies/" + policy + "/account/transfers-in").with(staff("UNDERWRITER", "uw")))
            .andExpect(jsonPath("$.length()").value(1));
        // 190,000 + 30,000 once (the fixture's transfer allocation is 0%), never 250,000.
        mockMvc.perform(get("/policies/" + policy + "/account").with(staff("UNDERWRITER", "uw")))
            .andExpect(jsonPath("$.balance.amount").value("220000.00"));
    }

    @Test
    void theSameWithdrawalSentTwiceIsAnsweredWithTheFirst() throws Exception {
        String policy = fundedAccount();
        String key = key();
        String body = "{\"amount\":\"10000.00\",\"payeeRef\":\"+255700000001\"}";
        String first = mockMvc.perform(post("/policies/" + policy + "/account/withdrawals").header("Idempotency-Key", key)
                .with(staff("UNDERWRITER", "staff-one")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // Without the key this would be a 422, "already in flight" -- the console would show a refusal
        // for a request that in fact succeeded.
        String second = mockMvc.perform(post("/policies/" + policy + "/account/withdrawals").header("Idempotency-Key", key)
                .with(staff("UNDERWRITER", "staff-one")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(second, "$.withdrawalId"))
            .isEqualTo(JsonPath.read(first, "$.withdrawalId"));
    }

    @Test
    void aRequestWithNoKeyIsA400() throws Exception {
        String policy = fundedAccount();
        mockMvc.perform(post("/policies/" + policy + "/account/transfers-in")
                .with(staff("FINANCE_OFFICER", "finance-one")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"30000.00\",\"sourceScheme\":\"NSSF\"}"))
            .andExpect(status().isBadRequest());
        // And nothing was credited.
        mockMvc.perform(get("/policies/" + policy + "/account").with(staff("UNDERWRITER", "uw")))
            .andExpect(jsonPath("$.balance.amount").value("190000.00"));
    }

    @Test
    void aKeyReusedForADifferentRequestIsRefused() throws Exception {
        String policy = fundedAccount();
        String key = key();
        mockMvc.perform(post("/policies/" + policy + "/account/transfers-in").header("Idempotency-Key", key)
                .with(staff("FINANCE_OFFICER", "finance-one")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"30000.00\",\"sourceScheme\":\"NSSF\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/policies/" + policy + "/account/adjustments").header("Idempotency-Key", key)
                .with(staff("FINANCE_OFFICER", "finance-one")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"500.00\",\"reason\":\"reusing a key\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(
                "This Idempotency-Key was already used for a different request. A new request needs a new key."));
    }

    // ---- Fixed-term deposits (2026-10-02) ------------------------------------------------------

    private String depositPolicy() {
        var issued = fixtures.issueDeposit(TENANT, new BigDecimal("1000000.00"), 3, LocalDate.now());
        fixtures.collectDeposit(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1000000.00"),
            LocalDate.now(), "+255700000777");
        return issued.policyNumber();
    }

    @Test
    void aDepositIsReadToSpec() throws Exception {
        String policy = depositPolicy();
        mockMvc.perform(get("/policies/" + policy + "/account/deposit").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.periods[0].termMonths").value(3))
            .andExpect(jsonPath("$.periods[0].principal.amount").value("1000000.00"))
            .andExpect(jsonPath("$.periods[0].status").value("RUNNING"))
            .andExpect(jsonPath("$.defaultPayeeRef").value("+255700000777"))
            .andExpect(jsonPath("$.termsOffered[2]").value(12))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aSavingsAccountIsNotADeposit() throws Exception {
        String policy = fundedAccount();
        mockMvc.perform(get("/policies/" + policy + "/account/deposit").with(staff("UNDERWRITER", "uw")))
            .andExpect(status().isNotFound());
    }

    @Test
    void theSameInstructionSentTwiceIsRecordedOnce() throws Exception {
        String policy = depositPolicy();
        String key = key();
        String body = "{\"action\":\"REINVEST\",\"termMonths\":6}";
        String first = mockMvc.perform(post("/policies/" + policy + "/account/deposit/maturity-instruction")
                .header("Idempotency-Key", key).with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/policies/" + policy + "/account/deposit/maturity-instruction")
                .header("Idempotency-Key", key).with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.instructionId")).isEqualTo(JsonPath.read(first, "$.instructionId"));
        // Read back, the deposit names the instruction it now carries -- and the spec admits it.
        mockMvc.perform(get("/policies/" + policy + "/account/deposit").with(staff("UNDERWRITER", "uw")))
            .andExpect(jsonPath("$.instruction.action").value("REINVEST"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aTopUpOnADepositIsA422InTheServersWords() throws Exception {
        String policy = depositPolicy();
        mockMvc.perform(post("/policies/" + policy + "/account/top-ups").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1000.00\",\"payerRef\":\"+255700000002\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("This is a fixed-term deposit: nothing can be added or taken out until it matures on "
                + LocalDate.now().plusMonths(3)));
    }

    @Test
    void onlyFinanceSeesTheAwaitingListOrPaysIt() throws Exception {
        mockMvc.perform(get("/deposits/awaiting-payee").with(staff("UNDERWRITER", "uw"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/deposits/awaiting-payee").with(staff("FINANCE_OFFICER", "fin")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
        String policy = depositPolicy();
        mockMvc.perform(post("/policies/" + policy + "/account/deposit/payout").header("Idempotency-Key", key())
                .with(staff("UNDERWRITER", "uw")).contentType(MediaType.APPLICATION_JSON).content("{\"payeeRef\":\"+255700000001\"}"))
            .andExpect(status().isForbidden());
        // Finance, on a deposit still running: nothing is waiting, refused in the server's words.
        mockMvc.perform(post("/policies/" + policy + "/account/deposit/payout").header("Idempotency-Key", key())
                .with(staff("FINANCE_OFFICER", "fin")).contentType(MediaType.APPLICATION_JSON).content("{\"payeeRef\":\"+255700000001\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("Policy " + policy + " has no matured deposit waiting for a payee"));
    }
}
