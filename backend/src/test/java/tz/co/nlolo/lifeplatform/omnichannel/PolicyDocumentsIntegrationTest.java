package tz.co.nlolo.lifeplatform.omnichannel;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
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
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer documents (2026-10-07) on a real savings plan: a premium collected through billing's own path
 * shows on the schedule with the day it arrived, its receipt reference and who paid; a redelivered confirmation
 * is not counted twice; the statement shows the money in with its running balance; both download as PDF and
 * Excel; and a customer reads only their own.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AccumulationTestFixtures.class)
class PolicyDocumentsIntegrationTest {

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
            DepositTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private MockMvc mockMvc;
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private BillingApi billingApi;
    @Autowired private tz.co.nlolo.lifeplatform.policy.api.PolicyApi policyApi;

    private static <T> T asTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(b -> b.claim("tenant_id", TENANT.toString())));
    }

    /** A savings plan whose first premium is paid through billing's own payment path, under one reference. */
    private String paidSavingsPlan(String reference) {
        String policyNumber = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY).policyNumber();
        InvoiceView first = asTenant(() -> billingApi.listInvoices(policyNumber, null)).stream()
            .min(Comparator.comparing(InvoiceView::dueDate)).orElseThrow();
        asTenant(() -> billingApi.applyConfirmedPayment(first.invoiceId(), new BigDecimal("50000.00"), "TZS", reference,
            "+255700000001"));
        return policyNumber;
    }

    @Test
    void theScheduleShowsEachPaymentWithItsDayReferenceAndPayerAndARedeliveryIsNotCountedTwice() throws Exception {
        String policyNumber = paidSavingsPlan("MM-DOC-1");
        InvoiceView first = asTenant(() -> billingApi.listInvoices(policyNumber, null)).stream()
            .min(Comparator.comparing(InvoiceView::dueDate)).orElseThrow();
        // The rail delivers the same confirmation again.
        asTenant(() -> billingApi.applyConfirmedPayment(first.invoiceId(), new BigDecimal("50000.00"), "TZS", "MM-DOC-1",
            "+255700000001"));

        mockMvc.perform(staff(get("/policies/" + policyNumber + "/payment-schedule")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policyholderName").value(org.hamcrest.Matchers.startsWith("Savings Test Life")))
            .andExpect(jsonPath("$.productName").value("Savings Test Product"))
            .andExpect(jsonPath("$.lines[0].number").value(1))
            .andExpect(jsonPath("$.lines[0].status").value("Paid"))
            .andExpect(jsonPath("$.lines[0].amountPaid").value(50000.0))
            .andExpect(jsonPath("$.lines[0].paidOn").value(TODAY.toString()))
            .andExpect(jsonPath("$.lines[0].receipts").value("MM-DOC-1"))
            .andExpect(jsonPath("$.lines[0].paidBy").value("+255700000001"))
            .andExpect(jsonPath("$.lines[0].balance").value(0.0))
            .andExpect(jsonPath("$.totals.paid").value(50000.0))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        assertThat(asTenant(() -> billingApi.listReceipts(policyNumber))).hasSize(1);
    }

    @Test
    void theStatementShowsMoneyInAndOutWithTheBalanceAfterEach() throws Exception {
        String policyNumber = paidSavingsPlan("MM-DOC-2");
        String from = TODAY.withDayOfYear(1).toString();
        String to = TODAY.withMonth(12).withDayOfMonth(31).toString();

        mockMvc.perform(staff(get("/policies/" + policyNumber + "/savings-statement").param("from", from).param("to", to)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openingBalance").value(0.0))
            .andExpect(jsonPath("$.lines[0].description").value("Premium collected"))
            .andExpect(jsonPath("$.lines[0].moneyIn").value(50000.0))
            .andExpect(jsonPath("$.lines[0].moneyOut").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.lines[0].balance").value(50000.0))
            .andExpect(jsonPath("$.lines[1].description").value(org.hamcrest.Matchers.startsWith("Allocation charge")))
            .andExpect(jsonPath("$.lines[1].moneyOut").value(2500.0))
            .andExpect(jsonPath("$.lines[1].balance").value(47500.0))
            .andExpect(jsonPath("$.closingBalance").value(47500.0))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));

        byte[] pdf = mockMvc.perform(staff(get("/policies/" + policyNumber + "/savings-statement/pdf")
                .param("from", from).param("to", to)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn().getResponse().getContentAsByteArray();
        try (var doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Savings account statement", policyNumber,
                "Opening balance", "Closing balance", "47,500.00");
        }
        mockMvc.perform(staff(get("/policies/" + policyNumber + "/payment-schedule/xlsx")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("payment-schedule-" + policyNumber + ".xlsx")));
    }

    /** The policy schedule and premium receipts (2026-10-08, the customer portal step 3). */
    @Test
    void thePolicyScheduleSaysWhatThePolicyIsAndEachPaymentHasAReceipt() throws Exception {
        String policyNumber = paidSavingsPlan("MM-DOC-4");
        UUID holder = asTenant(() -> policyApi.getPolicy(policyNumber)).policyholderPartyId();

        byte[] schedule = mockMvc.perform(get("/policies/" + policyNumber + "/policy-schedule/pdf")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", holder.toString()))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn().getResponse().getContentAsByteArray();
        try (var doc = Loader.loadPDF(schedule)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Policy schedule", policyNumber,
                "Savings Test Product", "Life insured", "Account value today", "Beneficiaries:",
                "The policy terms and conditions govern");
        }

        String receipts = mockMvc.perform(staff(get("/policies/" + policyNumber + "/receipts")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].reference").value("MM-DOC-4"))
            .andExpect(jsonPath("$[0].amount").value(50000.0))
            .andExpect(jsonPath("$[0].receivedOn").value(TODAY.toString()))
            // Paid on day one for the first period: it covers from today, not from the invoice's end-of-period date.
            .andExpect(jsonPath("$[0].coversFrom").value(TODAY.toString()))
            .andReturn().getResponse().getContentAsString();
        String receiptId = com.jayway.jsonpath.JsonPath.read(receipts, "$[0].receiptId");

        byte[] receipt = mockMvc.perform(staff(get("/policies/" + policyNumber + "/receipts/" + receiptId + "/pdf")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        try (var doc = Loader.loadPDF(receipt)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Premium receipt", policyNumber, "MM-DOC-4", "Cover paid for",
                "50,000.00", "Received with thanks");
        }

        // Another customer gets neither.
        mockMvc.perform(get("/policies/" + policyNumber + "/policy-schedule/pdf")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", UUID.randomUUID().toString()))))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/policies/" + policyNumber + "/receipts")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", UUID.randomUUID().toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerReadsOnlyTheirOwnPolicysDocuments() throws Exception {
        String policyNumber = paidSavingsPlan("MM-DOC-3");
        UUID holder = asTenant(() -> policyApi.getPolicy(policyNumber)).policyholderPartyId();

        mockMvc.perform(get("/policies/" + policyNumber + "/payment-schedule/pdf")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", holder.toString()))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"));
        mockMvc.perform(get("/policies/" + policyNumber + "/payment-schedule")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(b -> b.claim("tenant_id", TENANT.toString()).claim("party_id", UUID.randomUUID().toString()))))
            .andExpect(status().isForbidden());
    }
}
