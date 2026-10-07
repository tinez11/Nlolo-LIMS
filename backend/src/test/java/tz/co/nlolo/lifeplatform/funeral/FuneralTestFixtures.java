package tz.co.nlolo.lifeplatform.funeral;

import org.springframework.boot.test.context.TestComponent;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
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
    private final tz.co.nlolo.lifeplatform.policy.api.PolicyApi policyApi;

    public FuneralTestFixtures(ProductApi productApi, PartyApi partyApi, UnderwritingApi underwritingApi,
                               tz.co.nlolo.lifeplatform.policy.api.PolicyApi policyApi) {
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.underwritingApi = underwritingApi;
        this.policyApi = policyApi;
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

    // ---- group funeral schemes (2026-10-07) ----

    /**
     * Familia sold to group schemes only: the same plans, benefits and roles, each plan at 3,000 per member per month
     * (plan A) and 5,000 (plan B), and no premium table.
     */
    public Product publishGroupFamilia(UUID tenant) {
        return publishGroupFamilia(tenant, FuneralPlans.familia());
    }

    /** {@link #publishGroupFamilia(UUID)} with other claim rules: who a dependant's death pays, and a main member's death. */
    public Product publishGroupFamilia(UUID tenant, tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee payee,
                                       tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule onDeath) {
        return publishGroupFamilia(tenant, FuneralPlans.familia(6, payee, onDeath, false));
    }

    private Product publishGroupFamilia(UUID tenant, FuneralPlan f) {
        return publish(tenant, new FuneralPlan(true,
            f.plans().stream().map(p -> new tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption(p.planCode(), p.name(),
                new BigDecimal(p.planCode().equals("A") ? "3000.00" : "5000.00"))).toList(),
            f.benefits(), List.of(), f.roles(), f.maxPricedAge(), f.waitingPeriodMonths(), f.accidentWaivesWaiting(),
            f.dependantClaimPayee(), f.onMainMemberDeath(), f.freeCoverToPaidDate(),
            tz.co.nlolo.lifeplatform.product.api.FuneralSoldAs.GROUP));
    }

    /** An association: the scheme's policyholder, an organisation rather than a life. */
    public UUID association(UUID tenant) {
        return asTenant(tenant, () -> {
            int n = SEQ.incrementAndGet();
            return partyApi.registerCorporate("Chama cha Walimu " + n, "ASSOC-" + n + "-" + tenant.toString().substring(0, 4),
                "+25571800" + String.format("%04d", n % 10000), null, "test-staff").partyId();
        });
    }

    /** One life on a group funeral schedule, aged {@code age} last birthday today. */
    public static tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal.LifeLine life(String reference, FuneralRole role,
                                                                                        String name, int age) {
        return new tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal.LifeLine(reference, role, name,
            TODAY.minusYears(age).minusDays(30), null, null, false, null, null, null);
    }

    /** A group funeral proposal on {@code plan}, commencing today, with {@code lives}. */
    public static tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal groupProposal(String plan,
            List<tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal.LifeLine> lives) {
        return new tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal(
            tz.co.nlolo.lifeplatform.underwriting.api.GroupBenefitBasis.FUNERAL_PLAN, null, null, null, "TZS",
            List.of(), List.of(), null, "TZS", "MONTHLY", TODAY, null, plan, lives);
    }

    /** Opens a group funeral case for {@code association} on {@code product}. */
    public UUID openGroupCase(UUID tenant, Product product, UUID association,
                              tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal proposal) {
        return asTenant(tenant, () -> underwritingApi.openCase(association, product.productId(), product.versionId(), null,
            proposal, "staff-opener").caseId());
    }

    /** A group funeral case opened and accepted, which issues the scheme. Returns its policy number. */
    public String issueGroupScheme(UUID tenant, Product product, UUID association,
                                   tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal proposal) {
        UUID caseId = openGroupCase(tenant, product, association, proposal);
        decide(tenant, caseId, DecisionOutcome.ACCEPT, null);
        return asTenant(tenant, () -> policyApi.searchPolicies(association, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).policyNumber());
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

    /**
     * A plan-B monthly family accepted the normal way, which issues the policy with its lives. Returns the
     * policy number (the main member's only policy).
     */
    public String issueFamily(UUID tenant, Product product, UUID mainMember, List<FuneralApplication.Life> dependants) {
        UUID caseId = openFamilyCase(tenant, product, mainMember, dependants);
        decide(tenant, caseId, DecisionOutcome.ACCEPT, null);
        return asTenant(tenant, () -> policyApi.searchPolicies(mainMember, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).policyNumber());
    }

    /** {@link #issueFamily} and then the first premium collected, which puts the policy in force. */
    public String issueFamilyInForce(UUID tenant, Product product, UUID mainMember, List<FuneralApplication.Life> dependants,
                                     AnnuityTestFixtures annuityFixtures) {
        String policyNumber = issueFamily(tenant, product, mainMember, dependants);
        String premium = asTenant(tenant, () -> policyApi.getPolicy(policyNumber)).premiumAmount().toPlainString();
        annuityFixtures.collect(tenant, policyNumber, premium, TODAY);
        return policyNumber;
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
