package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteRefusedException;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.domain.FuneralQuoter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.CHILD;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.MAIN_MEMBER;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.PARENT;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.SPOUSE;

/** The one funeral quote: a line per life, the family total, the instalment rounded once. */
class FuneralQuoterTest {

    private static final LocalDate ON = LocalDate.of(2026, 10, 4);
    private static final FrequencyLoading FIVE_PERCENT_MONTHLY = new FrequencyLoading(new BigDecimal("5"), new BigDecimal("2"));

    private static FuneralLifeInput life(FuneralRole role, String name, int age) {
        return new FuneralLifeInput(role, name, ON.minusYears(age).minusDays(10), false);
    }

    private static List<FuneralLifeInput> family() {
        return List.of(life(MAIN_MEMBER, "Juma", 40), life(SPOUSE, "Asha", 38),
            life(CHILD, "Neema", 10), life(CHILD, "Baraka", 7), life(CHILD, "Zawadi", 3));
    }

    private static FuneralQuote quote(String plan, PremiumFrequency frequency, List<FuneralLifeInput> lives) {
        return FuneralQuoter.quote(FuneralPlans.familia(), FIVE_PERCENT_MONTHLY, new FuneralQuoteInput(plan, frequency, ON, lives));
    }

    private static void refused(Runnable r, String message) {
        assertThatThrownBy(r::run).isInstanceOf(FuneralQuoteRefusedException.class).hasMessage(message);
    }

    @Test
    void aFamilyOfFiveSumsEachLifesPremium() {
        FuneralQuote quote = quote("B", PremiumFrequency.MONTHLY, family());

        assertThat(quote.lines()).hasSize(5);
        assertThat(quote.lines().get(0).age()).isEqualTo(40);
        assertThat(quote.lines().get(0).benefit()).isEqualByComparingTo("2000000");
        assertThat(quote.lines().get(0).yearlyPremium()).isEqualByComparingTo("60000");
        assertThat(quote.lines().get(1).yearlyPremium()).isEqualByComparingTo("60000");
        assertThat(quote.lines().get(2).benefit()).isEqualByComparingTo("1000000");
        assertThat(quote.lines().get(2).yearlyPremium()).isEqualByComparingTo("6000");
        // 60,000 + 60,000 + 3 x 6,000
        assertThat(quote.totalYearlyPremium()).isEqualByComparingTo("138000");
        // 138,000 x 1.05 / 12 = 12,075.00
        assertThat(quote.instalment()).isEqualByComparingTo("12075.00");
        assertThat(quote.mainMemberBenefit()).isEqualByComparingTo("2000000");
        assertThat(quote.frequency()).isEqualTo(PremiumFrequency.MONTHLY);
    }

    /** Group funeral schemes: a version sold to groups only is never quoted to one family. */
    @Test
    void aVersionSoldToGroupSchemesOnlyIsNotQuotedToAFamily() {
        FuneralPlan f = FuneralPlans.familia();
        FuneralPlan groupOnly = new FuneralPlan(true, f.plans(), f.benefits(), List.of(), f.roles(), f.maxPricedAge(),
            f.waitingPeriodMonths(), f.accidentWaivesWaiting(), f.dependantClaimPayee(), f.onMainMemberDeath(),
            f.freeCoverToPaidDate(), tz.co.nlolo.lifeplatform.product.api.FuneralSoldAs.GROUP);
        refused(() -> FuneralQuoter.quote(groupOnly, FIVE_PERCENT_MONTHLY,
                new FuneralQuoteInput("B", PremiumFrequency.MONTHLY, ON, family())),
            "This product is sold to group schemes only; a scheme pays its plan's group rate per member");
    }

    /** A spouse and children included at 0: the family pays the main member's premium alone (a flat family rate). */
    @Test
    void dependantsIncludedAtZeroAddNothingToTheFamily() {
        FuneralPlan f = FuneralPlans.familia();
        FuneralPlan flat = new FuneralPlan(true, f.plans(), f.benefits(), f.premiums().stream()
                .map(p -> p.role() == MAIN_MEMBER ? p
                    : new tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow(p.planCode(), p.role(), p.ageFrom(),
                        p.ageTo(), BigDecimal.ZERO)).toList(),
            f.roles(), f.maxPricedAge(), f.waitingPeriodMonths(), f.accidentWaivesWaiting(), f.dependantClaimPayee(),
            f.onMainMemberDeath(), f.freeCoverToPaidDate());
        FuneralQuote quote = FuneralQuoter.quote(flat, FIVE_PERCENT_MONTHLY,
            new FuneralQuoteInput("B", PremiumFrequency.ANNUALLY, ON, family()));
        assertThat(quote.lines()).hasSize(5);
        assertThat(quote.instalment()).isEqualByComparingTo(quote.lines().get(0).yearlyPremium());
    }

    @Test
    void annualPaymentIsTheTotalUnloaded() {
        assertThat(quote("B", PremiumFrequency.ANNUALLY, family()).instalment()).isEqualByComparingTo("138000.00");
    }

    @Test
    void roundsOnceOnTheTotalNotOncePerLife() {
        // Three lives at 1,000.10 a year, quarterly with no loading: each alone is 250.025 a quarter.
        // Rounded per life that is 3 x 250.02 = 750.06; rounded once on the total it is 750.08 (HALF_EVEN of 750.075).
        FuneralPlan plan = FuneralPlans.of(FuneralPlans.plans(), FuneralPlans.benefits(),
            FuneralPlans.premiums().stream().map(p -> p.role() == CHILD && p.planCode().equals("B")
                ? new tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow("B", CHILD, p.ageFrom(), p.ageTo(), new BigDecimal("1000.10"))
                : p).toList(),
            FuneralPlans.roles());
        List<FuneralLifeInput> lives = List.of(life(MAIN_MEMBER, "Juma", 40), life(CHILD, "A", 5), life(CHILD, "B", 6), life(CHILD, "C", 7));
        FuneralQuote quote = FuneralQuoter.quote(plan, FrequencyLoading.none(),
            new FuneralQuoteInput("B", PremiumFrequency.QUARTERLY, ON, lives));

        BigDecimal expected = new BigDecimal("63000.30").divide(new BigDecimal("4"), 2, RoundingMode.HALF_EVEN);
        assertThat(quote.instalment()).isEqualByComparingTo(expected);
        assertThat(quote.instalment()).isEqualByComparingTo("15750.08");
    }

    @Test
    void anUnknownPlanIsRefused() {
        refused(() -> quote("Z", PremiumFrequency.MONTHLY, family()), "There is no plan Z on this product");
    }

    @Test
    void aSinglePremiumIsRefused() {
        refused(() -> quote("B", PremiumFrequency.SINGLE, family()), "A funeral plan is paid monthly, quarterly or annually");
    }

    @Test
    void exactlyOneMainMember() {
        refused(() -> quote("B", PremiumFrequency.MONTHLY, List.of(life(SPOUSE, "Asha", 38))),
            "A funeral plan covers exactly one main member");
    }

    @Test
    void aSecondSpouseIsRefused() {
        List<FuneralLifeInput> lives = new ArrayList<>(family());
        lives.add(life(SPOUSE, "Mwanaisha", 35));
        refused(() -> quote("B", PremiumFrequency.MONTHLY, lives), "At most 1 spouse may be covered");
    }

    @Test
    void aSeventhChildIsRefused() {
        List<FuneralLifeInput> lives = new ArrayList<>(List.of(life(MAIN_MEMBER, "Juma", 40)));
        for (int i = 0; i < 7; i++) {
            lives.add(life(CHILD, "Child " + i, 2 + i));
        }
        refused(() -> quote("B", PremiumFrequency.MONTHLY, lives), "At most 6 children may be covered");
    }

    @Test
    void aChildTooOldForEntryIsRefused() {
        List<FuneralLifeInput> lives = List.of(life(MAIN_MEMBER, "Juma", 45), life(CHILD, "Neema", 21));
        refused(() -> quote("B", PremiumFrequency.MONTHLY, lives), "Neema: a child must be 0 to 20 at entry, not 21");
    }

    @Test
    void aRoleThePlanDoesNotCoverIsRefused() {
        FuneralPlan noParents = FuneralPlans.of(FuneralPlans.plans(),
            FuneralPlans.benefits().stream().filter(b -> !(b.planCode().equals("A") && b.role() == PARENT)).toList(),
            FuneralPlans.premiums().stream().filter(p -> !(p.planCode().equals("A") && p.role() == PARENT)).toList(),
            FuneralPlans.roles());
        refused(() -> FuneralQuoter.quote(noParents, FrequencyLoading.none(), new FuneralQuoteInput("A", PremiumFrequency.MONTHLY, ON,
            List.of(life(MAIN_MEMBER, "Juma", 40), life(PARENT, "Bibi", 70)))), "Plan A does not cover parents");
    }

    @Test
    void aRoleTheProductDoesNotCoverIsRefused() {
        FuneralPlan noExtended = FuneralPlans.of(FuneralPlans.plans(),
            FuneralPlans.benefits().stream().filter(b -> b.role() != FuneralRole.EXTENDED).toList(),
            FuneralPlans.premiums().stream().filter(p -> p.role() != FuneralRole.EXTENDED).toList(),
            FuneralPlans.roles().stream().filter(r -> r.role() != FuneralRole.EXTENDED).toList());
        refused(() -> FuneralQuoter.quote(noExtended, FrequencyLoading.none(), new FuneralQuoteInput("B", PremiumFrequency.MONTHLY, ON,
            List.of(life(MAIN_MEMBER, "Juma", 40), life(FuneralRole.EXTENDED, "Kaka", 30)))),
            "This product does not cover an extended family member");
    }

    @Test
    void aMissingDateOfBirthIsRefused() {
        refused(() -> quote("B", PremiumFrequency.MONTHLY, List.of(life(MAIN_MEMBER, "Juma", 40),
            new FuneralLifeInput(CHILD, "Neema", null, false))), "Neema: the date of birth is required");
    }

    @Test
    void onlyAChildCanBeAStudent() {
        refused(() -> quote("B", PremiumFrequency.MONTHLY, List.of(life(MAIN_MEMBER, "Juma", 40),
            new FuneralLifeInput(SPOUSE, "Asha", ON.minusYears(30), true))), "Asha: only a child can be marked as a student");
    }

    @Test
    void oneLifeIsPricedAtAnyAgeTheTableCovers() {
        // Past the main member's entry ages (65), but still priced: the anniversary re-prices an existing life.
        assertThat(FuneralQuoter.yearlyPremiumAt(FuneralPlans.familia(), "B", MAIN_MEMBER, 80)).isEqualByComparingTo("150000");
        refused(() -> FuneralQuoter.yearlyPremiumAt(FuneralPlans.familia(), "B", MAIN_MEMBER, 101),
            "Plan B has no premium for a main member aged 101");
    }

    /** Familia sold to groups -- the joiner rule refuses any product that is not. */
    private static FuneralPlan groupFamilia() {
        FuneralPlan f = FuneralPlans.familia();
        return new FuneralPlan(true, f.plans(), f.benefits(), List.of(), f.roles(), f.maxPricedAge(),
            f.waitingPeriodMonths(), f.accidentWaivesWaiting(), f.dependantClaimPayee(), f.onMainMemberDeath(),
            f.freeCoverToPaidDate(), tz.co.nlolo.lifeplatform.product.api.FuneralSoldAs.GROUP);
    }

    @Test
    void aJoinerIsCheckedAloneBesideTheCountAlreadyInItsRole() {
        assertThat(FuneralQuoter.joinerProblems(groupFamilia(), "A", ON, life(CHILD, "Zuri", 12), 5)).isEmpty();
        assertThat(FuneralQuoter.joinerProblems(groupFamilia(), "A", ON, life(CHILD, "Zuri", 12), 6))
            .containsExactly("At most 6 children may be covered, not 7");
        assertThat(FuneralQuoter.joinerProblems(groupFamilia(), "A", ON, life(SPOUSE, "Mwanaisha", 30), 1))
            .containsExactly("At most 1 spouse may be covered, not 2");
        assertThat(FuneralQuoter.joinerProblems(groupFamilia(), "A", ON, life(CHILD, "Old", 30), 0))
            .singleElement().asString().startsWith("Old: a child must be 0 to 20");
        assertThat(FuneralQuoter.joinerProblems(groupFamilia(), "A", ON, life(MAIN_MEMBER, "Second", 30), 0))
            .containsExactly("Second: a family has exactly one main member");
        assertThat(FuneralQuoter.joinerProblems(FuneralPlans.familia(), "A", ON, life(CHILD, "Zuri", 12), 0))
            .containsExactly("This product is not sold to group schemes");
    }
}
