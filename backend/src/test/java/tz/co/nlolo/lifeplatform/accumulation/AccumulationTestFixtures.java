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
                ProductCategory.ENDOWMENT, "TZS", "actuary");
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
