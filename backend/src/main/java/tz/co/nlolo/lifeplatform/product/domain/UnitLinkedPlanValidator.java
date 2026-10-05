package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.FundDirectory;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.AllocationBand;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.MortalityBasis;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.MortalityRow;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a UNIT_LINKED version's terms must be before it can be sold (spec §4). Pure and static, as the
 * other plan validators are, so the whole rule set is unit-tested in milliseconds; the fund register is asked
 * through the FundDirectory SPI, which a test passes as a lambda.
 */
public final class UnitLinkedPlanValidator {

    /** The platform's own frequencies -- PremiumFrequency's names, never a second hand-kept list. */
    private static final Set<String> FREQUENCIES = java.util.Arrays.stream(tz.co.nlolo.lifeplatform.product.api.PremiumFrequency.values())
        .map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private UnitLinkedPlanValidator() {}

    /**
     * @param directory the fund register, or null when none is available (a context without unitlinked)
     * @param minEntryAge the version's eligibility floor, or null: the mortality table must start there (0 if none)
     * @param priced whether the version carries a base-rate table or rating factors -- a unit-linked version
     *               carries neither, since its cost of insurance comes from the mortality table
     */
    public static void validate(ProductCategory category, UnitLinkedPlan plan, String productCurrency,
                                Integer minEntryAge, FundDirectory directory, boolean priced) {
        boolean authored = plan != null && plan.unitLinked();
        if (category != ProductCategory.UNIT_LINKED) {
            if (authored) {
                fail("Unit-linked terms are only valid on a UNIT_LINKED product");
            }
            return;
        }
        if (!authored) {
            fail("A UNIT_LINKED version needs unit-linked terms: the funds it offers, its allocation, fee and mortality table");
        }
        if (priced) {
            fail("A UNIT_LINKED version is not priced by base rates or rating factors: its cost of insurance comes from its mortality table");
        }
        checkFunds(plan, productCurrency, directory);
        checkAllocation(plan.allocationBands());
        if (plan.monthlyPolicyFee() == null || plan.monthlyPolicyFee().signum() < 0) {
            fail("A monthly policy fee of zero or more is required");
        }
        checkMortality(plan, minEntryAge == null ? 0 : minEntryAge);
        if (plan.deathRule() == null) {
            fail("A UNIT_LINKED version says what death pays: the higher of sum assured and fund value, or both");
        }
        if (plan.minimumPremiumYears() != null && (plan.minimumPremiumYears() < 1 || plan.minimumPremiumYears() > 50)) {
            fail("Minimum premium-paying years must be between 1 and 50, or left blank for none");
        }
        if (plan.minimumSurrenderYears() < 0 || plan.minimumSurrenderYears() > 50) {
            fail("Minimum years before surrender must be between 0 and 50");
        }
        if (plan.lowFundWarningMonths() < 1 || plan.lowFundWarningMonths() > 60) {
            fail("The low-fund warning must be between 1 and 60 months of charges");
        }
        checkPremiums(plan);
        checkOptions(plan.options());
    }

    /** U2's terms (V26): each feature is both-or-neither of its pair, and the surrender charge runs on from year 1. */
    private static void checkOptions(UnitLinkedOptions o) {
        if ((o.freeSwitchesPerYear() == null) != (o.switchFee() == null)) {
            fail("Switching needs both the free switches per year and the fee for each switch after them");
        }
        if (o.freeSwitchesPerYear() != null && (o.freeSwitchesPerYear() < 0 || o.switchFee().signum() < 0)) {
            fail("Free switches and the switch fee are zero or more");
        }
        if ((o.minimumWithdrawal() == null) != (o.minimumRemainingValue() == null)) {
            fail("Withdrawals need both the minimum withdrawal and the minimum value left in the policy");
        }
        if (o.minimumWithdrawal() != null && (o.minimumWithdrawal().signum() <= 0 || o.minimumRemainingValue().signum() < 0)) {
            fail("A minimum withdrawal is greater than zero and the minimum value left is zero or more");
        }
        if ((o.topUpAllocationPercent() == null) != (o.minimumTopUp() == null)) {
            fail("Top-ups need both their allocation percent and the minimum top-up");
        }
        if (o.topUpAllocationPercent() != null && (o.topUpAllocationPercent().signum() <= 0
                || o.topUpAllocationPercent().compareTo(HUNDRED) > 0 || o.minimumTopUp().signum() <= 0)) {
            fail("A top-up allocation percent is greater than 0 and at most 100, and the minimum top-up greater than zero");
        }
        List<UnitLinkedOptions.SurrenderChargeBand> bands = o.surrenderCharges();
        for (int i = 0; i < bands.size(); i++) {
            UnitLinkedOptions.SurrenderChargeBand b = bands.get(i);
            Integer previousEnd = i == 0 ? Integer.valueOf(0) : bands.get(i - 1).toYear();
            if (previousEnd == null || b.fromYear() != previousEnd + 1) {
                fail("Surrender charge bands must run on from year 1 without gaps; band " + (i + 1) + " starts at year "
                    + b.fromYear());
            }
            if (b.percent() == null || b.percent().signum() < 0 || b.percent().compareTo(HUNDRED) > 0) {
                fail("A surrender charge is between 0% and 100%");
            }
            if (b.toYear() != null && b.toYear() < b.fromYear()) {
                fail("Surrender charge band " + (i + 1) + " ends before it starts");
            }
        }
        if (!bands.isEmpty() && bands.get(bands.size() - 1).toYear() != null) {
            fail("The last surrender charge band must be open-ended");
        }
    }

    private static void checkFunds(UnitLinkedPlan plan, String productCurrency, FundDirectory directory) {
        if (plan.fundCodes().isEmpty()) {
            fail("A UNIT_LINKED version offers at least one fund");
        }
        Set<String> seen = new HashSet<>();
        for (String code : plan.fundCodes()) {
            if (!seen.add(code)) {
                fail("Fund " + code + " is offered twice");
            }
        }
        if (directory == null) {
            fail("No fund register is available to check the funds against");
        }
        for (String code : plan.fundCodes()) {
            FundDirectory.FundSummary fund = directory.find(code)
                .orElseThrow(() -> new InvalidProductVersionException("Fund " + code + " is not in the fund register"));
            if (!fund.open()) {
                fail("Fund " + code + " is closed and takes no new money");
            }
            if (!fund.currency().equals(productCurrency)) {
                fail("Fund " + code + " is priced in " + fund.currency() + "; this product is " + productCurrency);
            }
        }
    }

    private static void checkAllocation(List<AllocationBand> given) {
        if (given.isEmpty()) {
            fail("A UNIT_LINKED version needs at least one allocation band");
        }
        List<AllocationBand> bands = given.stream().sorted(Comparator.comparingInt(AllocationBand::fromYear)).toList();
        int expected = 1;
        for (int i = 0; i < bands.size(); i++) {
            AllocationBand b = bands.get(i);
            if (b.percent() == null || b.percent().signum() <= 0 || b.percent().compareTo(BigDecimal.valueOf(100)) > 0) {
                fail("An allocation percent is greater than 0 and at most 100");
            }
            if (b.fromYear() != expected) {
                fail("Allocation bands must run on from year 1 without gaps; band " + (i + 1) + " starts at year " + b.fromYear());
            }
            boolean last = i == bands.size() - 1;
            if (last && b.toYear() != null) {
                fail("The last allocation band must be open-ended");
            }
            if (!last && b.toYear() == null) {
                fail("Only the last allocation band may be open-ended");
            }
            if (!last && b.toYear() < b.fromYear()) {
                fail("Allocation band " + (i + 1) + " ends before it starts");
            }
            if (!last) {
                expected = b.toYear() + 1;
            }
        }
    }

    private static void checkMortality(UnitLinkedPlan plan, int minEntryAge) {
        if (plan.mortalityBasis() == null) {
            fail("A mortality basis is required: unisex or by sex");
        }
        if (plan.mortality().isEmpty()) {
            fail("A UNIT_LINKED version needs a mortality table to charge the cost of insurance from");
        }
        for (MortalityRow r : plan.mortality()) {
            if (r.annualRatePerMille() == null || r.annualRatePerMille().signum() < 0) {
                fail("A mortality rate per 1,000 is zero or more");
            }
        }
        if (plan.mortalityBasis() == MortalityBasis.UNISEX) {
            if (plan.mortality().stream().anyMatch(r -> r.sex() != null)) {
                fail("A UNISEX mortality table has no sex on its rows");
            }
            checkMortalitySeries(plan.mortality(), minEntryAge, null);
        } else {
            if (plan.mortality().stream().anyMatch(r -> r.sex() == null || !(r.sex().equals("FEMALE") || r.sex().equals("MALE")))) {
                fail("A BY_SEX mortality table needs FEMALE and MALE rows for every band");
            }
            List<MortalityRow> female = plan.mortality().stream().filter(r -> "FEMALE".equals(r.sex())).toList();
            List<MortalityRow> male = plan.mortality().stream().filter(r -> "MALE".equals(r.sex())).toList();
            if (female.isEmpty() || male.isEmpty()) {
                fail("A BY_SEX mortality table needs FEMALE and MALE rows for every band");
            }
            checkMortalitySeries(female, minEntryAge, "FEMALE");
            checkMortalitySeries(male, minEntryAge, "MALE");
        }
    }

    private static void checkMortalitySeries(List<MortalityRow> given, int minEntryAge, String sex) {
        String which = sex == null ? "" : " (" + sex + ")";
        List<MortalityRow> rows = given.stream().sorted(Comparator.comparingInt(MortalityRow::ageFrom)).toList();
        if (rows.get(0).ageFrom() > minEntryAge) {
            fail("The mortality table" + which + " must start at the minimum entry age " + minEntryAge
                + "; it starts at " + rows.get(0).ageFrom());
        }
        for (int i = 0; i < rows.size(); i++) {
            MortalityRow r = rows.get(i);
            boolean last = i == rows.size() - 1;
            if (last && r.ageTo() != null) {
                fail("The last mortality band" + which + " must be open-ended (a whole-of-life policy has no maximum age)");
            }
            if (!last) {
                if (r.ageTo() == null) {
                    fail("Only the last mortality band" + which + " may be open-ended");
                }
                if (rows.get(i + 1).ageFrom() != r.ageTo() + 1) {
                    fail("Mortality bands" + which + " must run on without gaps or overlaps; age "
                        + r.ageTo() + " is followed by " + rows.get(i + 1).ageFrom());
                }
            }
        }
    }

    private static void checkPremiums(UnitLinkedPlan plan) {
        if (plan.premiumMinimums().isEmpty()) {
            fail("A UNIT_LINKED version sets a minimum premium for each frequency it takes");
        }
        Set<String> seen = new HashSet<>();
        for (UnitLinkedPlan.PremiumMinimum m : plan.premiumMinimums()) {
            if (m.frequency() == null || !FREQUENCIES.contains(m.frequency())) {
                fail("A premium frequency is one of " + FREQUENCIES);
            }
            if (!seen.add(m.frequency())) {
                fail("The " + m.frequency() + " minimum premium is given twice");
            }
            if (m.amount() == null || m.amount().signum() <= 0) {
                fail("A minimum premium is greater than zero");
            }
        }
        BigDecimal min = plan.sumAssuredMultipleMin();
        BigDecimal max = plan.sumAssuredMultipleMax();
        if (min == null || max == null || min.signum() <= 0) {
            fail("The sum assured's minimum and maximum multiples of the annual premium are required, and greater than zero");
        }
        if (max.compareTo(min) < 0) {
            fail("The sum assured's maximum multiple " + max.stripTrailingZeros().toPlainString()
                + " is below its minimum " + min.stripTrailingZeros().toPlainString());
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
