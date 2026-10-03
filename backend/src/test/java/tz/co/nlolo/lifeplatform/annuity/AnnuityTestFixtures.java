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

    public AnnuityTestFixtures(PartyApi partyApi, ProductApi productApi, UnderwritingApi underwritingApi, PolicyApi policyApi) {
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.underwritingApi = underwritingApi;
        this.policyApi = policyApi;
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
