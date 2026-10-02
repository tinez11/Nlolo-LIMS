package tz.co.nlolo.lifeplatform.bonus;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/** A real with-profits endowment and a real in-force policy on it -- AccumulationTestFixtures' shape. */
@TestComponent
public class BonusTestFixtures {

    public static final BonusPlan COMPOUND_NONE = new BonusPlan(true, BonusMethod.COMPOUND, false, BonusSurrenderBasis.NONE, List.of());
    public static final BonusPlan SIMPLE_NONE = new BonusPlan(true, BonusMethod.SIMPLE, false, BonusSurrenderBasis.NONE, List.of());

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;

    public BonusTestFixtures(PartyApi partyApi, ProductApi productApi, PolicyApi policyApi,
                             ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record Issued(String policyNumber, UUID productId, UUID productVersionId) {}

    /** A 1,000,000 with-profits endowment, ACTIVE, issued today. cashValue may be CashValuePlan.none(). */
    public Issued issue(UUID tenant, BonusPlan plan, CashValuePlan cashValue) {
        return issue(tenant, plan, cashValue, LocalDate.now(), 180);
    }

    /** The same, commenced on {@code commencement} for {@code termMonths}. */
    public Issued issue(UUID tenant, BonusPlan plan, CashValuePlan cashValue, LocalDate commencement, int termMonths) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            int n = SEQ.incrementAndGet();
            PartyView applicant = partyApi.registerIndividual("Bonus Test Life " + n, LocalDate.of(1985, 1, 1),
                "+25571700" + String.format("%04d", n % 10000), null, "test-agent");
            ProductSummaryView product = productApi.createProduct("WP-" + n + "-" + tenant.toString().substring(0, 4),
                "With-Profits Test", ProductCategory.ENDOWMENT, "TZS", "actuary");
            UUID versionId = publishVersion(tenant, product.productId(), plan, cashValue);
            PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
                versionId, new BigDecimal("1000000.00"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null,
                List.of(), "bonus test", commencement, termMonths, null, null, null);
            String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
            policyApi.activateOnFirstPremium(policyNumber);
            return new Issued(policyNumber, product.productId(), versionId);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** Publishes (and makes active for new business) a with-profits ENDOWMENT version paying 100% of SA at maturity. */
    public UUID publishVersion(UUID tenant, UUID productId, BonusPlan plan, CashValuePlan cashValue) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            PayoutPlan payout = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
                PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
            productApi.publishVersion(productId, IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-49", BigDecimal.ONE, 30, 49),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING,
                cashValue, payout, AccumulationPlan.none(), DepositPlan.none(), plan, "actuary");
            return productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** A lifecycle event dated in the PAST -- how a test puts a status on an earlier valuation date. */
    public void publishAt(UUID tenant, String eventType, Instant occurredAt, Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(
            new DomainEventEnvelope<>(UUID.randomUUID(), eventType, 1, tenant, occurredAt, null, payload)));
    }

    public void publish(UUID tenant, String eventType, Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(DomainEventEnvelope.of(eventType, tenant, payload)));
    }

    public void publishEnvelopeTwice(DomainEventEnvelope<?> envelope) {
        tx.executeWithoutResult(status -> publisher.publishEvent(envelope));
        tx.executeWithoutResult(status -> publisher.publishEvent(envelope));
    }
}
