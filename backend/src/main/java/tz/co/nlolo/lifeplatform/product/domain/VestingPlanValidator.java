package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;

/**
 * Everything a CHECK cannot say about a deferred annuity's vesting terms (product step 5, D2). Pure
 * and static, like {@link AnnuityPlanValidator}, which already checked the forms and their coverage
 * over the vesting window; the console mirrors this message for message.
 *
 * <p>A deferred annuity is an ANNUITY version that saves in an account first. Vesting terms without
 * the account would be a pension with nothing to vest; the account without vesting terms would be a
 * savings plan with no way ever to pay its income.
 */
public final class VestingPlanValidator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private VestingPlanValidator() {}

    public static void validate(ProductCategory category, AnnuityPlan plan, AccumulationPlan accumulation,
                                EligibilityBounds bounds) {
        VestingTerms vesting = plan != null ? plan.vesting() : null;
        boolean account = accumulation != null && accumulation.isAccount();
        if (vesting == null) {
            if (category == ProductCategory.ANNUITY && account) {
                fail("An annuity that saves in an account must state its vesting terms");
            }
            return;
        }
        if (category != ProductCategory.ANNUITY) {
            fail("Vesting terms are only for an ANNUITY product");
        }
        if (!account) {
            fail("Vesting terms are only for an annuity that saves in an account before it vests");
        }
        if (vesting.minVestingAge() < 0 || vesting.maxVestingAge() > 120 || vesting.minVestingAge() > vesting.maxVestingAge()) {
            fail("The vesting window runs from a minimum to a maximum vesting age, each between 0 and 120");
        }
        AnnuityForm defaultForm = plan.form(vesting.defaultFormCode()).orElse(null);
        if (defaultForm == null) {
            fail("The default form " + vesting.defaultFormCode() + " is not one of this version's forms");
        }
        if (defaultForm.joint()) {
            fail("The default form " + defaultForm.formCode()
                + " is joint-life; a pension that vests with no instruction vests on one life");
        }
        if (plan.frequency(vesting.defaultFrequency()).isEmpty()) {
            fail("The default frequency " + vesting.defaultFrequency() + " is not one of this version's frequencies");
        }
        BigDecimal cap = vesting.maxCommutationPercent();
        if (cap == null || cap.signum() < 0 || cap.compareTo(HUNDRED) > 0) {
            fail("The lump-sum cap must be between 0% and 100% of the balance");
        }
        if (vesting.surrenderBeforeVesting() == null) {
            fail("A deferred annuity must state whether it can be surrendered before it vests");
        }
        if (bounds != null && bounds.maxEntryAge() != null && bounds.maxEntryAge() >= vesting.maxVestingAge()) {
            fail("The maximum entry age must be below the maximum vesting age, so every customer can reach a vesting age");
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
