package tz.co.nlolo.lifeplatform.funeral;

import org.springframework.boot.test.context.TestComponent;
import tz.co.nlolo.lifeplatform.party.api.Address;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.product.FuneralPlans;
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
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.PayoutPlan;
import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/** Funeral products, families, cases and policies, through the real APIs. Later tasks add to it. */
@TestComponent
public class FuneralTestFixtures {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 5% for paying monthly, 2% quarterly: what the quote tests price against. */
    public static final FrequencyLoading LOADING = new FrequencyLoading(new BigDecimal("5"), new BigDecimal("2"));

    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final UnderwritingApi underwritingApi;

    public FuneralTestFixtures(ProductApi productApi, PartyApi partyApi, UnderwritingApi underwritingApi) {
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.underwritingApi = underwritingApi;
    }

    public record Product(UUID productId, UUID versionId) {}

    /** "Familia" ({@link FuneralPlans#familia()}): plans A and B, the spec's default roles and claim rules. */
    public Product publishFamilia(UUID tenant) {
        return publish(tenant, FuneralPlans.familia());
    }

    public Product publish(UUID tenant, FuneralPlan plan) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            ProductSummaryView product = productApi.createProduct("FUN-" + n + "-" + tenant.toString().substring(0, 4),
                "Familia Test", ProductCategory.FUNERAL, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(), List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, List.of(), EligibilityBounds.none(), LOADING, ANY_FILING, CashValuePlan.none(),
                PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of()), AccumulationPlan.none(),
                DepositPlan.none(), BonusPlan.none(), AnnuityPlan.none(), plan, "actuary");
            return new Product(product.productId(),
                productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId());
        });
    }

    /** A main member aged {@code age} last birthday today. */
    public UUID person(UUID tenant, int age, Sex sex) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            return partyApi.registerIndividual(new IndividualRegistration("Mwanachama " + n, TODAY.minusYears(age).minusDays(30),
                "+25571700" + String.format("%04d", n % 10000), null, sex, null, IdentityDocument.none(), null, null, null,
                null, Address.none()), "test-agent").partyId();
        });
    }

    /** A dependant aged {@code age} last birthday today. */
    public static FuneralApplication.Life dependant(FuneralRole role, String name, int age) {
        return new FuneralApplication.Life(role, name, TODAY.minusYears(age).minusDays(30), null, null, false);
    }

    /** Spouse 38 and children 10, 7 and 3: the spec's family of five with a main member. */
    public static List<FuneralApplication.Life> family() {
        return List.of(dependant(FuneralRole.SPOUSE, "Asha", 38), dependant(FuneralRole.CHILD, "Neema", 10),
            dependant(FuneralRole.CHILD, "Baraka", 7), dependant(FuneralRole.CHILD, "Zawadi", 3));
    }

    /** Opens a case for {@code mainMember} at {@code sumAssured}, paid {@code frequency}, from today; no application. */
    public UUID openCase(UUID tenant, Product product, UUID mainMember, String sumAssured, String frequency) {
        return asTenant(tenant, () -> underwritingApi.openCase(mainMember, product.productId(), product.versionId(),
            new BigDecimal(sumAssured), "TZS", null,
            new ProposalDetails(null, null, null, TODAY, null, null, frequency, List.of()), "staff-opener").caseId());
    }

    /** Opens a plan-B monthly case with the application recorded. */
    public UUID openFamilyCase(UUID tenant, Product product, UUID mainMember, List<FuneralApplication.Life> dependants) {
        UUID caseId = openCase(tenant, product, mainMember, "2000000.00", "MONTHLY");
        asTenant(tenant, () -> underwritingApi.recordFuneralApplication(caseId, "B", dependants, "staff-opener"));
        return caseId;
    }

    /** An assessment by one underwriter, then {@code outcome} by another (separation of duties). */
    public void decide(UUID tenant, UUID caseId, DecisionOutcome outcome, BigDecimal loading) {
        asTenant(tenant, () -> {
            underwritingApi.submitAssessment(caseId, AssessmentType.MEDICAL, "Healthy family", new BigDecimal("10"), "assessor-one");
            return underwritingApi.decide(caseId, new UnderwritingApi.DecisionInput(outcome, loading, "Family accepted"),
                "senior-two", true);
        });
    }
}
