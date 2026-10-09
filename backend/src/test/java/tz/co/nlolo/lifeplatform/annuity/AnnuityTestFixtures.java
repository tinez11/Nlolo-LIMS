package tz.co.nlolo.lifeplatform.annuity;

import org.springframework.boot.test.context.TestComponent;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.Address;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/** Annuity products, annuitants and cases, through the real APIs (product step 5). */
@TestComponent
public class AnnuityTestFixtures {

    public static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final UnderwritingApi underwritingApi;
    private final PolicyApi policyApi;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public AnnuityTestFixtures(PartyApi partyApi, ProductApi productApi, UnderwritingApi underwritingApi, PolicyApi policyApi,
                               org.springframework.context.ApplicationEventPublisher publisher,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.underwritingApi = underwritingApi;
        this.policyApi = policyApi;
        this.publisher = publisher;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * Opens a case with its choice and has a second underwriter accept it on proof of age: the normal
     * path, which auto-issues the policy PROPOSED. Returns the policy number.
     */
    public String buy(UUID tenant, Product product, UUID annuitant, String price, AnnuityChoice choice) {
        return buy(tenant, product, annuitant, price, choice, null);
    }

    /**
     * As above, the case stating when cover starts -- for a test that dates events (a death, a collection) before
     * today: a policy covers nothing before it started (audit 2026-10-07).
     */
    public String buy(UUID tenant, Product product, UUID annuitant, String price, AnnuityChoice choice, LocalDate commencement) {
        UUID caseId = asTenant(tenant, () -> {
            UUID id = underwritingApi.openCase(annuitant, product.productId(), product.versionId(), new BigDecimal(price),
                "TZS", null, new ProposalDetails(null, null, null, commencement), "staff-opener").caseId();
            if (choice != null) {
                underwritingApi.recordAnnuityChoice(id, choice, "staff-opener");
            }
            return id;
        });
        asTenant(tenant, () -> underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome.ACCEPT, null,
                "Age proven by passport", true), "senior-two", true));
        return asTenant(tenant, () -> policyApi.searchPolicies(annuitant, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).policyNumber());
    }

    /** What billing publishes when the single premium is collected -- the real payload keys. */
    public void collect(UUID tenant, String policyNumber, String amount, LocalDate on) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("invoiceId", UUID.randomUUID());
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", UUID.randomUUID());
        payload.put("amount", java.util.Map.of("amount", amount, "currencyCode", "TZS"));
        // 09:00 in Dar es Salaam: the civil day is unambiguous whatever the server's clock zone.
        payload.put("collectedAt", on.atTime(9, 0).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toInstant().toString());
        payload.put("paidToDate", on.toString());
        publish(tenant, "billing.PremiumCollected", payload);
    }

    public void publish(UUID tenant, String eventType, java.util.Map<String, Object> payload) {
        tx.executeWithoutResult(status -> publisher.publishEvent(
            tz.co.nlolo.lifeplatform.DomainEventEnvelope.of(eventType, tenant, payload)));
    }

    public record Product(UUID productId, UUID versionId) {}

    // ---- Plans over ages 60-62 ----

    private static List<AnnuityRateRow> unisex(String r60, String r61, String r62) {
        return List.of(new AnnuityRateRow(null, 60, null, null, new BigDecimal(r60)),
            new AnnuityRateRow(null, 61, null, null, new BigDecimal(r61)),
            new AnnuityRateRow(null, 62, null, null, new BigDecimal(r62)));
    }

    public static AnnuityForm lifeOnly() {
        return new AnnuityForm("LIFE-0G", 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, unisex("72", "74", "76"));
    }

    public static AnnuityForm guaranteed10() {
        return new AnnuityForm("LIFE-10G", 10, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, unisex("70", "72", "74"));
    }

    public static AnnuityForm bySex() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 60; age <= 62; age++) {
            rows.add(new AnnuityRateRow("FEMALE", age, null, null, new BigDecimal("70")));
            rows.add(new AnnuityRateRow("MALE", age, null, null, new BigDecimal("75")));
        }
        return new AnnuityForm("LIFE-BS", 0, false, null, BigDecimal.ONE, false, AnnuityRateBasis.BY_SEX, rows);
    }

    /** Joint, 50% to the survivor; differences -10..15 in two bands. */
    public static AnnuityForm joint50() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 60; age <= 62; age++) {
            rows.add(new AnnuityRateRow(null, age, -10, 4, new BigDecimal("62")));
            rows.add(new AnnuityRateRow(null, age, 5, 15, new BigDecimal("65")));
        }
        return new AnnuityForm("JOINT-50", 0, true, new BigDecimal("50"), BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, rows);
    }

    public static AnnuityPlan plan(AnnuityTiming timing, AnnuityForm... forms) {
        boolean joint = List.of(forms).stream().anyMatch(AnnuityForm::joint);
        return new AnnuityPlan(true, timing, 12, joint ? -10 : null, joint ? 15 : null, "ACT/ANN/TEST",
            LocalDate.of(2026, 1, 1), List.of(forms),
            List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.9800")),
                    new AnnuityFrequencyFactor("ANNUAL", BigDecimal.ONE)));
    }

    /** Every form above, paid in ARREARS. */
    public static AnnuityPlan everyForm() {
        return plan(AnnuityTiming.ARREARS, lifeOnly(), guaranteed10(), bySex(), joint50());
    }

    // ---- Through the APIs, as the tenant ----

    public Product publish(UUID tenant, AnnuityPlan plan) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            ProductSummaryView product = productApi.createProduct("ANN-" + n + "-" + tenant.toString().substring(0, 4),
                "Annuity Test", ProductCategory.ANNUITY, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-80", BigDecimal.ONE, 18, 80),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), new EligibilityBounds(60, 62, null, null, null, null), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()),
                AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), plan, "actuary");
            return new Product(product.productId(), productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
        });
    }

    // ---- Deferred annuities (D2): save from 18-55, vest 55-70 ----

    /** LIFE-0G and JOINT-50 with rates over the vesting window 55-70; MONTHLY and ANNUAL. */
    public static AnnuityPlan deferredPlan(boolean surrenderBeforeVesting) {
        List<AnnuityRateRow> life = new ArrayList<>();
        List<AnnuityRateRow> joint = new ArrayList<>();
        for (int age = 55; age <= 70; age++) {
            life.add(new AnnuityRateRow(null, age, null, null, new BigDecimal(60 + age - 55)));
            joint.add(new AnnuityRateRow(null, age, -10, 15, new BigDecimal(50 + age - 55)));
        }
        return new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, -10, 15, "ACT/ANN/TEST", LocalDate.of(2026, 1, 1),
            List.of(new AnnuityForm("LIFE-0G", 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, life),
                    new AnnuityForm("JOINT-50", 0, true, new BigDecimal("50"), BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, joint)),
            List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.9800")),
                    new AnnuityFrequencyFactor("ANNUAL", BigDecimal.ONE)),
            new VestingTerms(55, 70, "LIFE-0G", "MONTHLY", new BigDecimal("25"), surrenderBeforeVesting));
    }

    /** A 3% account that charges nothing, so a balance is the contributions plus interest. */
    public static final AccumulationPlan PENSION_ACCOUNT = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"),
        BigDecimal.ZERO, List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

    /** A deferred annuity, entry ages 18-55; locked unless {@code surrenderBeforeVesting}. */
    public Product publishDeferred(UUID tenant, boolean surrenderBeforeVesting) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            ProductSummaryView product = productApi.createProduct("DEF-" + n + "-" + tenant.toString().substring(0, 4),
                "Deferred Annuity Test", ProductCategory.ANNUITY,
                tz.co.nlolo.lifeplatform.product.api.PortfolioCode.PEN, "TZS", "actuary");
            return publishDeferredVersion(tenant, product.productId(), deferredPlan(surrenderBeforeVesting));
        });
    }

    /** A further version of a deferred product -- the one a later vesting is priced on (spec Q7). */
    public Product publishDeferredVersion(UUID tenant, UUID productId, AnnuityPlan plan) {
        return asTenant(tenant, () -> {
            productApi.publishVersion(productId, IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-80", BigDecimal.ONE, 18, 80),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), new EligibilityBounds(18, 55, null, null, null, null), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()),
                PENSION_ACCOUNT, DepositPlan.none(), BonusPlan.none(), plan, "actuary");
            return new Product(productId, productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId());
        });
    }

    /** A single LIFE form named {@code formCode} at {@code rate} per mille for every vesting age, defaulting to it. */
    public static AnnuityPlan deferredPlanWith(String formCode, int rate) {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 55; age <= 70; age++) {
            rows.add(new AnnuityRateRow(null, age, null, null, new BigDecimal(rate)));
        }
        return new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, null, null, "ACT/ANN/TEST-2", LocalDate.of(2026, 6, 1),
            List.of(new AnnuityForm(formCode, 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, rows)),
            List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.9800")),
                    new AnnuityFrequencyFactor("ANNUAL", BigDecimal.ONE)),
            new VestingTerms(55, 70, formCode, "MONTHLY", new BigDecimal("25"), Boolean.FALSE));
    }

    /** The party record corrected to a new sex (null = not recorded), everything else kept. */
    public void amendSex(UUID tenant, UUID partyId, Sex sex) {
        asTenant(tenant, () -> {
            var p = partyApi.getPartyDetail(partyId);
            return partyApi.amendIndividual(partyId, new IndividualRegistration(p.displayName(), p.dateOfBirth(), p.phoneNumber(),
                p.email(), sex, null, IdentityDocument.none(), null, null, null, null, Address.none()), "test-staff");
        });
    }

    /** The party record corrected to a new date of birth, everything else kept. */
    public void amendDateOfBirth(UUID tenant, UUID partyId, LocalDate dateOfBirth) {
        asTenant(tenant, () -> {
            var p = partyApi.getPartyDetail(partyId);
            return partyApi.amendIndividual(partyId, new IndividualRegistration(p.displayName(), dateOfBirth, p.phoneNumber(),
                p.email(), p.sex(), null, IdentityDocument.none(), null, null, null, null, Address.none()), "test-staff");
        });
    }

    /** A person born on {@code dateOfBirth}, of the given sex (null = not recorded). */
    public UUID personBorn(UUID tenant, LocalDate dateOfBirth, Sex sex) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            return partyApi.registerIndividual(new IndividualRegistration("Saver " + n, dateOfBirth,
                "+25571900" + String.format("%04d", n % 10000), null, sex, null, IdentityDocument.none(), null, null, null,
                null, Address.none()), "test-agent").partyId();
        });
    }

    /**
     * Opens a deferred annuity case: {@code contribution} per {@code frequency} payment from
     * {@code commencement}, recording the retirement age when given.
     */
    public UUID openDeferredCase(UUID tenant, Product product, UUID saver, String contribution, String frequency,
                                 LocalDate commencement, Integer retirementAge) {
        return asTenant(tenant, () -> {
            UUID caseId = underwritingApi.openCase(saver, product.productId(), product.versionId(), new BigDecimal(contribution),
                "TZS", null, new ProposalDetails(null, null, null, commencement, null, null, frequency, List.of()),
                "staff-opener").caseId();
            if (retirementAge != null) {
                underwritingApi.recordDeferredAnnuityChoice(caseId, retirementAge, "staff-opener");
            }
            return caseId;
        });
    }

    /**
     * A deferred annuity bought the normal way: opened from today at 200,000.00 a month, retiring at
     * {@code retirementAge}, and accepted on proof of age -- which issues the account policy. Returns
     * the policy number.
     */
    public String issueDeferred(UUID tenant, Product product, UUID saver, int retirementAge) {
        UUID caseId = openDeferredCase(tenant, product, saver, "200000.00", "MONTHLY", TODAY, retirementAge);
        asTenant(tenant, () -> underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome.ACCEPT, null,
                "Age proven by passport", true), "senior-two", true));
        return asTenant(tenant, () -> policyApi.searchPolicies(saver, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).policyNumber());
    }

    /** An ordinary WHOLE_LIFE product, entry ages 18-80, for "everyone else is unchanged" checks. */
    public Product publishOrdinary(UUID tenant) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            ProductSummaryView product = productApi.createProduct("WL-" + n + "-" + tenant.toString().substring(0, 4),
                "Whole Life Test", ProductCategory.WHOLE_LIFE, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-80", BigDecimal.ONE, 18, 80),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()), "actuary");
            return new Product(product.productId(), productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
        });
    }

    /** A person aged {@code age} last birthday today, of the given sex (null = not recorded). */
    public UUID person(UUID tenant, int age, Sex sex) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            return partyApi.registerIndividual(new IndividualRegistration("Annuitant " + n, TODAY.minusYears(age).minusDays(30),
                "+25571800" + String.format("%04d", n % 10000), null, sex, null, IdentityDocument.none(), null, null, null,
                null, Address.none()), "test-agent").partyId();
        });
    }

    /**
     * An annuity policy in force at once, by the MIGRATION exception path -- for tests of what happens
     * downstream of issue, where the case and the premium are not the point.
     */
    public String issueInForce(UUID tenant, Product product, UUID annuitant, String price) {
        return asTenant(tenant, () -> policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(annuitant,
            product.productId(), product.versionId(), new BigDecimal(price), "TZS", new BigDecimal(price), "TZS", "SINGLE",
            null, List.of(), "annuity test", null, null, null, null, IssuanceBasis.MIGRATION), "test-staff").policyNumber());
    }

    /** Opens a case for {@code annuitant} at {@code price}, as staff-opener, and records the choice when given. */
    public UUID openCase(UUID tenant, Product product, UUID annuitant, String price, AnnuityChoice choice) {
        return asTenant(tenant, () -> {
            UUID caseId = underwritingApi.openCase(annuitant, product.productId(), product.versionId(), new BigDecimal(price),
                "TZS", null, ProposalDetails.selfInsured(), "staff-opener").caseId();
            if (choice != null) {
                underwritingApi.recordAnnuityChoice(caseId, choice, "staff-opener");
            }
            return caseId;
        });
    }

    public static <T> T asTenant(UUID tenant, Supplier<T> work) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant);
        try {
            return work.get();
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
