package tz.co.nlolo.lifeplatform.benefitpayout;

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
 * A real product, a real party and a real in-force policy, through the real APIs -- so a payout
 * test exercises the platform rather than rows someone inserted by hand.
 *
 * <p>Shared because every payout test needs the same three things, and because a fixture copied
 * four times is four places to miss the next rule a publish or an issuance starts demanding.
 */
@TestComponent
public class PayoutTestFixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public PayoutTestFixtures(PartyApi partyApi, ProductApi productApi, PolicyApi policyApi,
                              ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** An ACTIVE endowment commencing today, on a fresh version carrying {@code plan}. */
    public String issueEndowment(UUID tenant, PayoutPlan plan, BigDecimal sumAssured, int termMonths) {
        return issueEndowment(tenant, plan, sumAssured, termMonths, LocalDate.now());
    }

    /** An ACTIVE endowment whose risk commenced on {@code commencement} -- how a test reaches a
     *  policy year in the past without waiting five years for it. */
    public String issueEndowment(UUID tenant, PayoutPlan plan, BigDecimal sumAssured, int termMonths,
                                 LocalDate commencement) {
        return issue(tenant, ProductCategory.ENDOWMENT, plan, sumAssured, termMonths, commencement, "MONTHLY");
    }

    public String issue(UUID tenant, ProductCategory category, PayoutPlan plan, BigDecimal sumAssured,
                        int termMonths, LocalDate commencement, String premiumFrequency) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Payout Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571400" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct(
                "PAYOUT-" + n + "-" + tenant.toString().substring(0, 4), "Payout Test Product", category, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), plan, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
                versionId, sumAssured, "TZS", new BigDecimal("50000.00"), "TZS", premiumFrequency, null, List.of(),
                "payout test", commencement, termMonths, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return policyNumber;
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    /** What billing publishes when a premium clears, so the AFTER_COMMIT listener really fires. */
    public void collectPremium(UUID tenant, String policyNumber, BigDecimal amount, LocalDate paidToDate) {
        publish(tenant, "billing.PremiumCollected", Map.of(
            "policyNumber", policyNumber,
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", "TZS"),
            "paidToDate", paidToDate.toString()));
    }

    /** Publish any envelope inside a COMMITTED transaction -- an AFTER_COMMIT listener fires on
     *  nothing less, and a test that forgets this sees a silent no-op. */
    public void publish(UUID tenant, String eventType, Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(DomainEventEnvelope.of(eventType, tenant, payload)));
    }
}
