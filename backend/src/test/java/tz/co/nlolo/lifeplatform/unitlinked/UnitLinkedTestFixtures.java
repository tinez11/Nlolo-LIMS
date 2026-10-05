package tz.co.nlolo.lifeplatform.unitlinked;

import org.springframework.boot.test.context.TestComponent;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.PayoutPlan;
import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.AllocationBand;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.MortalityRow;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.PremiumMinimum;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.CreateFund;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundPriceView;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * Funds, prices and unit-linked products the way staff make them: a fund in the register, a price proposed by
 * finance and approved by an admin. A price dated YESTERDAY is always past its cut-off, so it approves at once
 * without a fixed clock; a price for a date whose cut-off has not passed is how a test proves the refusal.
 */
@TestComponent
public class UnitLinkedTestFixtures {

    public static final String FINANCE = "staff.finance";
    public static final String ADMIN = "staff.admin";
    private static final AtomicInteger SEQ = new AtomicInteger();

    public record Product(UUID productId, UUID versionId) {}

    private final UnitLinkedApi api;
    private final ProductApi productApi;
    private final UnderwritingApi underwritingApi;
    private final PolicyApi policyApi;
    private final FuneralTestFixtures people;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public UnitLinkedTestFixtures(UnitLinkedApi api, ProductApi productApi, UnderwritingApi underwritingApi,
                                  PolicyApi policyApi, FuneralTestFixtures people,
                                  org.springframework.context.ApplicationEventPublisher publisher,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.api = api;
        this.productApi = productApi;
        this.underwritingApi = underwritingApi;
        this.policyApi = policyApi;
        this.people = people;
        this.publisher = publisher;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** Dar es Salaam civil time: {@code eat(D, 15, 0)} is after a 14:00 cut-off on D. */
    public static java.time.Instant eat(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toInstant();
    }

    /**
     * billing.PremiumCollected for {@code invoiceId} at the instant chosen -- the binding is fixed by it, so a test
     * can stand a premium before or after a cut-off on any day without a clock.
     */
    public void collectAt(UUID tenant, String policyNumber, String amount, java.time.Instant collectedAt, UUID invoiceId) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("invoiceId", invoiceId);
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", UUID.randomUUID());
        payload.put("amount", java.util.Map.of("amount", amount, "currencyCode", "TZS"));
        payload.put("collectedAt", collectedAt.toString());
        payload.put("paidToDate", java.time.LocalDate.now().toString());
        tx.executeWithoutResult(status -> publisher.publishEvent(
            tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("billing.PremiumCollected", tenant, payload)));
    }

    public FundView fund(UUID tenant, String code, LocalTime cutOff) {
        return asTenant(tenant, () -> api.createFund(new CreateFund(code, code + " fund", "TZS", "EQUITY",
            new BigDecimal("1.5000"), cutOff), ADMIN));
    }

    public FundView fund(UUID tenant, String code) {
        return fund(tenant, code, LocalTime.of(14, 0));
    }

    /** Proposed by finance, approved by an admin -- two people, as every price is. */
    public FundPriceView approvedPrice(UUID tenant, String code, LocalDate date, String price) {
        return asTenant(tenant, () -> {
            FundPriceView proposed = api.proposePrice(code, date, new BigDecimal(price), "fixture", FINANCE);
            return api.approvePrice(proposed.priceId(), ADMIN);
        });
    }

    /**
     * The plan's standard terms: funds EQ1 and BD1, years 1-2 at 90% and 98% after, a 2,000 fee, a unisex table
     * from 18 (1.2, 4.5 and 25 per mille), HIGHER_OF, EXHAUSTION, no surrender minimum, a three-month low-fund
     * warning, MONTHLY from 50,000 and 5x to 20x the annual premium.
     */
    public static UnitLinkedPlan standardTerms(List<String> fundCodes) {
        return UnitLinkedPlan.of(fundCodes,
            List.of(new AllocationBand(1, 2, new BigDecimal("90")), new AllocationBand(3, null, new BigDecimal("98"))),
            new BigDecimal("2000.00"), UnitLinkedPlan.MortalityBasis.UNISEX,
            List.of(new MortalityRow(18, 39, null, new BigDecimal("1.2")), new MortalityRow(40, 59, null, new BigDecimal("4.5")),
                new MortalityRow(60, null, null, new BigDecimal("25"))),
            UnitLinkedPlan.DeathRule.HIGHER_OF, UnitLinkedPlan.LapseRule.EXHAUSTION, null, 0, 3,
            List.of(new PremiumMinimum("MONTHLY", new BigDecimal("50000.00"))), new BigDecimal("5"), new BigDecimal("20"));
    }

    /** A UNIT_LINKED product on these terms; its funds must already be in the tenant's register. */
    public Product publish(UUID tenant, UnitLinkedPlan terms) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            ProductSummaryView product = productApi.createProduct("UL-" + n + "-" + tenant.toString().substring(0, 4),
                "Wekeza Test", ProductCategory.UNIT_LINKED, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(), List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), new EligibilityBounds(18, 65, null, null, null, null), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), PayoutPlan.authored(new PayoutTerms(14, null, null, null), List.of()),
                AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), AnnuityPlan.none(), FuneralPlan.none(),
                terms, "actuary");
            return new Product(product.productId(),
                productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
        });
    }

    /** Funds EQ1 and BD1 in the register and a product offering both on the standard terms. */
    public Product publishStandard(UUID tenant) {
        fund(tenant, "EQ1");
        fund(tenant, "BD1");
        return publish(tenant, standardTerms(List.of("EQ1", "BD1")));
    }

    /** A life aged {@code age} last birthday today, registered as staff register one. */
    public UUID person(UUID tenant, int age) {
        return people.person(tenant, age, Sex.MALE);
    }

    /** Opens a case for {@code sumAssured} and records the choice on it; returns the case id. */
    public UUID openCase(UUID tenant, Product product, UUID life, UnitLinkedChoice choice) {
        UUID caseId = asTenant(tenant, () -> underwritingApi.openCase(life, product.productId(), product.versionId(),
            choice.sumAssured(), "TZS", null,
            new ProposalDetails(null, null, null, LocalDate.now(), null, null, choice.frequency(), List.of()), "staff-opener").caseId());
        asTenant(tenant, () -> underwritingApi.recordUnitLinkedChoice(caseId, choice, "staff-opener"));
        return caseId;
    }

    /** 60% EQ1 and 40% BD1, 100,000 a month (1,200,000 a year), and cover of 6,000,000 -- the 5x floor. */
    public static UnitLinkedChoice standardChoice() {
        return new UnitLinkedChoice(List.of(new UnitLinkedChoice.Split("EQ1", 60), new UnitLinkedChoice.Split("BD1", 40)),
            new BigDecimal("100000.00"), "MONTHLY", new BigDecimal("6000000.00"));
    }

    /** Sold the normal way: case opened with the choice, assessed by one person, accepted by another. */
    public String sell(UUID tenant, Product product, UUID life, UnitLinkedChoice choice) {
        UUID caseId = openCase(tenant, product, life, choice);
        people.decide(tenant, caseId, DecisionOutcome.ACCEPT, null);
        return asTenant(tenant, () -> policyApi.searchPolicies(life, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).policyNumber());
    }

    /** Moves a policy's issue and commencement to {@code issued}, so its charge dates fall on days that can be priced. */
    public void backdate(String policyNumber, LocalDate issued) {
        jdbc.update("UPDATE policy.policy SET issue_date = ?, commencement_date = ? WHERE policy_number = ?", issued, issued, policyNumber);
    }

    /** EQ1 and BD1 priced on {@code date}, by two people each. */
    public void priceBoth(UUID tenant, LocalDate date, String eq, String bd) {
        approvedPrice(tenant, "EQ1", date, eq);
        approvedPrice(tenant, "BD1", date, bd);
    }

    /** Any event, committed, so AFTER_COMMIT listeners hear it as they would in production. */
    public void publish(UUID tenant, String eventType, java.util.Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(
            tz.co.nlolo.lifeplatform.DomainEventEnvelope.of(eventType, tenant, payload)));
    }

    /**
     * The standard product on funds whose cut-off is one second past midnight: anything that happens today binds to
     * TOMORROW, whatever the hour -- so a test can know the bound date of a registration stamped with the real now.
     */
    public Product publishStandardBindingTomorrow(UUID tenant, UnitLinkedPlan terms) {
        fund(tenant, "EQ1", LocalTime.of(0, 0, 1));
        fund(tenant, "BD1", LocalTime.of(0, 0, 1));
        return publish(tenant, terms);
    }

    /**
     * U2's standard terms: 2 free switches a policy year then 5,000 each; withdrawals from 100,000 leaving at least
     * 500,000; top-ups at 98% from 50,000; a surrender charge of 10% in year 1, 5% in years 2-5 and nothing after.
     * Withdrawals leave cover unchanged (spec Q4's default).
     */
    public static tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions standardOptions() {
        return new tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions(2, new BigDecimal("5000.00"),
            new BigDecimal("100000.00"), new BigDecimal("500000.00"), false, new BigDecimal("98"), new BigDecimal("50000.00"),
            List.of(new tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions.SurrenderChargeBand(1, 1, new BigDecimal("10")),
                new tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions.SurrenderChargeBand(2, 5, new BigDecimal("5")),
                new tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions.SurrenderChargeBand(6, null, BigDecimal.ZERO)));
    }

    /** {@link #standardTerms} with another death rule. */
    public static UnitLinkedPlan withDeathRule(UnitLinkedPlan.DeathRule rule) {
        UnitLinkedPlan t = standardTerms(List.of("EQ1", "BD1"));
        return UnitLinkedPlan.of(t.fundCodes(), t.allocationBands(), t.monthlyPolicyFee(), t.mortalityBasis(), t.mortality(),
            rule, t.lapseRule(), t.minimumPremiumYears(), t.minimumSurrenderYears(), t.lowFundWarningMonths(),
            t.premiumMinimums(), t.sumAssuredMultipleMin(), t.sumAssuredMultipleMax());
    }
}
