package tz.co.nlolo.lifeplatform.accumulation;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * A real ACCOUNT-basis product and a real in-force policy on it, through the real APIs -- the
 * PayoutTestFixtures shape, for the same reason: a fixture copied into every test class is that many
 * places to miss the next rule a publish or an issuance starts demanding.
 */
@TestComponent
public class AccumulationTestFixtures {

    /** 3% guaranteed, 50,000 minimum for withdrawals; 5% allocation in year 1, 1% after, 1,000 a month. */
    public static final AccumulationPlan SAVINGS = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"),
        new BigDecimal("50000.00"), List.of(
            new AccumulationChargeRow(1, 1, new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("1000.00")),
            new AccumulationChargeRow(2, null, new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("1000.00"))));

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public AccumulationTestFixtures(PartyApi partyApi, ProductApi productApi, PolicyApi policyApi,
                                    ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Issued(String policyNumber, UUID productId, UUID productVersionId) {}

    public Issued issueSavingsPlan(UUID tenant, AccumulationPlan plan, LocalDate commencement) {
        return issue(tenant, plan, commencement, 240);
    }

    public Issued issue(UUID tenant, AccumulationPlan plan, LocalDate commencement, int termMonths) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Savings Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571500" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct(
                "SAVE-" + n + "-" + tenant.toString().substring(0, 4), "Savings Test Product",
                ProductCategory.ENDOWMENT,
                // An account is a savings contract: SAV, or publishing refuses it (2026-10-09).
                plan.isAccount() ? tz.co.nlolo.lifeplatform.product.api.PortfolioCode.SAV
                    : tz.co.nlolo.lifeplatform.product.api.PortfolioCode.END, "TZS", "actuary");
            // Both shapes are ENDOWMENTs, and step 2's validator makes an endowment carry a schedule
            // and every individual product a free-look period -- so the SCALE control is a plain
            // sum-assured endowment, not a version with no payout plan, which would be refused.
            PayoutPlan payout = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
                PayoutKind.MATURITY, null, null,
                plan.isAccount() ? PayoutAmountBasis.ACCOUNT_VALUE : PayoutAmountBasis.PERCENT_OF_SA,
                new BigDecimal("100"), null)));
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), payout, plan, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
                versionId, new BigDecimal("1000000.00"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null,
                List.of(), "savings test", commencement, termMonths, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return new Issued(policyNumber, product.productId(), versionId);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /**
     * The user's grid (fixed-term deposit spec §1), rates for the term. An amount between their bands
     * goes to the higher one (their answer, 2026-10-02), so each band past the first starts one cent
     * above the previous band's top.
     */
    public static final DepositPlan USER_GRID = grid(new String[][] {
        {"500000", "3", "4", "5"}, {"5000000.01", "4", "5", "6"}, {"10000000.01", "5", "6", "7"}, {"20000000.01", "6", "7", "8"}});

    /** Bands of {start, 3-month rate, 6-month rate, 12-month rate}. */
    public static DepositPlan grid(String[][] bands) {
        int[] terms = {3, 6, 12};
        java.util.List<DepositRateRow> rows = new java.util.ArrayList<>();
        for (String[] band : bands) {
            for (int t = 0; t < terms.length; t++) {
                rows.add(new DepositRateRow(new BigDecimal(band[0]), terms[t], new BigDecimal(band[t + 1])));
            }
        }
        return new DepositPlan(rows);
    }

    /** A fixed-term deposit product on the user's grid, and an in-force deposit on it (not yet paid). */
    public Issued issueDeposit(UUID tenant, BigDecimal amount, int termMonths, LocalDate commencement) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Deposit Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571600" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct(
                "FTD-" + n + "-" + tenant.toString().substring(0, 4), "Fixed deposit", ProductCategory.ENDOWMENT,
                tz.co.nlolo.lifeplatform.product.api.PortfolioCode.DEP, "TZS", "actuary");
            UUID versionId = publishDepositVersion(tenant, product.productId(), USER_GRID);
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
                versionId, amount, "TZS", amount, "TZS", "SINGLE", null,
                List.of(), "deposit test", commencement, termMonths, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return new Issued(policyNumber, product.productId(), versionId);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** Publishes (and so makes active for new business) a deposit version on an existing product. */
    public UUID publishDepositVersion(UUID tenant, UUID productId, DepositPlan plan) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            productApi.publishVersion(productId, IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING, CashValuePlan.none(),
                PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()), AccumulationPlan.none(), plan, "actuary");
            return productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** billing.PremiumCollected for a deposit, with the number it was collected from (null: a cash receipt). */
    public void collectDeposit(UUID tenant, String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate on, String payerRef) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("invoiceId", invoiceId);
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", UUID.randomUUID());
        payload.put("amount", Map.of("amount", amount.toPlainString(), "currencyCode", "TZS"));
        // Midnight UTC is 03:00 in Dar es Salaam, the same civil day accumulation reads.
        payload.put("collectedAt", on.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString());
        payload.put("paidToDate", on.toString());
        if (payerRef != null) payload.put("payerRef", payerRef);
        publish(tenant, "billing.PremiumCollected", payload);
    }

    /** What billing publishes when an invoice reaches PAID -- the real payload keys. */
    public void collectPremium(UUID tenant, String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn) {
        publish(tenant, "billing.PremiumCollected", Map.of(
            "invoiceId", invoiceId,
            "policyNumber", policyNumber,
            "policyholderPartyId", UUID.randomUUID(),
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", "TZS"),
            "collectedAt", collectedOn.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(),
            "paidToDate", collectedOn.toString()));
    }

    public void publish(UUID tenant, String eventType, Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(DomainEventEnvelope.of(eventType, tenant, payload)));
    }
}
