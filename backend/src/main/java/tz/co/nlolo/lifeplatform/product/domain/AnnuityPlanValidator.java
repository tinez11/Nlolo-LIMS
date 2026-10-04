package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything a CHECK cannot say about an ANNUITY version (product step 5). Pure and static, like
 * BonusPlanValidator; the console mirrors it message for message.
 *
 * <p>The coverage rule is the important one. A purchase is priced by looking up one cell, and a
 * missing cell would leave someone who has already been accepted unable to be priced -- so every
 * age the version accepts, every sex a BY_SEX form needs, and every age difference a joint form
 * offers must have exactly one rate. Refuse at publish, never default at purchase.
 */
public final class AnnuityPlanValidator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN = BigDecimal.TEN;

    private AnnuityPlanValidator() {}

    public static void validate(ProductCategory category, AnnuityPlan plan, EligibilityBounds bounds, CashValuePlan cashValue,
                                AccumulationPlan accumulation, DepositPlan deposit, BonusPlan bonus, PayoutPlan payout) {
        boolean hasTerms = plan != null && plan.annuity();
        if (category != ProductCategory.ANNUITY) {
            if (hasTerms) {
                fail("Annuity terms are only for an ANNUITY product");
            }
            return;
        }
        if (!hasTerms) {
            fail("An ANNUITY version must state its annuity terms: forms, rates and frequencies");
        }
        checkExclusions(cashValue, accumulation, deposit, bonus, payout);
        checkTerms(plan, bounds);
        Set<String> codes = new HashSet<>();
        for (AnnuityForm form : plan.forms()) {
            checkSettings(form);
            if (!codes.add(form.formCode())) {
                fail("Form code " + form.formCode() + " appears more than once");
            }
        }
        checkDistinctSettings(plan.forms());
        boolean anyJoint = plan.forms().stream().anyMatch(AnnuityForm::joint);
        if (anyJoint && (plan.jointAgeDifferenceMin() == null || plan.jointAgeDifferenceMax() == null)) {
            fail("A joint-life form needs the version's range of age differences");
        }
        for (AnnuityForm form : plan.forms()) {
            checkCoverage(plan, form, bounds.minEntryAge(), bounds.maxEntryAge());
        }
        checkFrequencies(plan.frequencies());
    }

    private static void checkExclusions(CashValuePlan cashValue, AccumulationPlan accumulation, DepositPlan deposit,
                                        BonusPlan bonus, PayoutPlan payout) {
        if (cashValue != null && cashValue.isPresent()) {
            fail("An ANNUITY version cannot carry a cash-value table");
        }
        if ((accumulation != null && accumulation.isAccount()) || (deposit != null && deposit.isDeposit())) {
            fail("An ANNUITY version cannot be valued by an account or as a deposit");
        }
        if (bonus != null && bonus.participating()) {
            fail("An ANNUITY version cannot be with-profits");
        }
        if (payout != null && payout.authored() && !payout.rows().isEmpty()) {
            fail("An ANNUITY version carries no payout schedule; its income is set by its forms");
        }
    }

    private static void checkTerms(AnnuityPlan plan, EligibilityBounds bounds) {
        if (plan.forms().isEmpty()) {
            fail("An annuity version needs at least one annuity form");
        }
        if (plan.frequencies().isEmpty()) {
            fail("An annuity version needs at least one payment frequency");
        }
        if (plan.timing() == null) {
            fail("An annuity version must state whether income is paid in ARREARS or in ADVANCE");
        }
        if (plan.basisReference() == null || plan.basisReference().isBlank() || plan.basisDate() == null) {
            fail("An annuity rate table needs the actuarial basis it was issued under");
        }
        if (plan.proofOfLifeIntervalMonths() < 1 || plan.proofOfLifeIntervalMonths() > 24) {
            fail("An annuity's proof-of-life interval must be between 1 and 24 months");
        }
        if (bounds == null || bounds.minEntryAge() == null || bounds.maxEntryAge() == null) {
            fail("An annuity version needs minimum and maximum entry ages, so its grids can be checked for gaps");
        }
    }

    private static void checkSettings(AnnuityForm form) {
        String f = "Form " + form.formCode() + ": ";
        if (form.guaranteeYears() < 0 || form.guaranteeYears() > 30) {
            fail(f + "a guaranteed period must be between 0 and 30 years");
        }
        if (!form.joint() && form.survivorPercent() != null) {
            fail(f + "a survivor percentage is only for a joint-life form");
        }
        if (form.joint() && (form.survivorPercent() == null || form.survivorPercent().compareTo(BigDecimal.ONE) < 0
                || form.survivorPercent().compareTo(HUNDRED) > 0)) {
            fail(f + "a joint-life form needs a survivor percentage between 1 and 100");
        }
        if (form.escalationPercent() == null || form.escalationPercent().signum() < 0 || form.escalationPercent().compareTo(TEN) > 0) {
            fail(f + "escalation must be between 0% and 10% a year");
        }
    }

    /** Same four settings and rate basis twice is one product offered under two names. */
    private static void checkDistinctSettings(List<AnnuityForm> forms) {
        for (int i = 0; i < forms.size(); i++) {
            for (int j = i + 1; j < forms.size(); j++) {
                AnnuityForm a = forms.get(i);
                AnnuityForm b = forms.get(j);
                if (a.guaranteeYears() == b.guaranteeYears() && a.joint() == b.joint()
                        && sameNumber(a.survivorPercent(), b.survivorPercent())
                        && sameNumber(a.escalationPercent(), b.escalationPercent())
                        && a.capitalProtected() == b.capitalProtected() && a.rateBasis() == b.rateBasis()) {
                    fail("Forms " + a.formCode() + " and " + b.formCode() + " have the same settings");
                }
            }
        }
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static void checkCoverage(AnnuityPlan plan, AnnuityForm form, int minAge, int maxAge) {
        String code = form.formCode();
        for (AnnuityRateRow r : form.rates()) {
            if (form.rateBasis() == AnnuityRateBasis.UNISEX && r.sex() != null) {
                fail("Form " + code + ": a UNISEX form's rates carry no sex");
            }
            if (form.rateBasis() == AnnuityRateBasis.BY_SEX && r.sex() == null) {
                fail("Form " + code + ": a BY_SEX form's rates each name a sex");
            }
            if (r.annualRatePerMille() == null || r.annualRatePerMille().signum() <= 0) {
                fail("Form " + code + ": every rate must be greater than zero");
            }
        }
        List<String> sexes = form.rateBasis() == AnnuityRateBasis.BY_SEX
            ? List.of("FEMALE", "MALE") : Collections.singletonList(null);
        for (String sex : sexes) {
            for (int age = minAge; age <= maxAge; age++) {
                final int a = age;
                List<AnnuityRateRow> atAge = form.rates().stream()
                    .filter(r -> r.age() == a && Objects.equals(r.sex(), sex)).toList();
                if (!form.joint()) {
                    if (atAge.isEmpty()) {
                        fail(sex == null ? "Form " + code + " has no rate for age " + a
                            : "Form " + code + " has no rate for a " + sex + " aged " + a);
                    }
                    continue;
                }
                for (int d = plan.jointAgeDifferenceMin(); d <= plan.jointAgeDifferenceMax(); d++) {
                    final int diff = d;
                    long hits = atAge.stream().filter(r -> r.ageDifferenceFrom() != null && r.ageDifferenceTo() != null
                        && r.ageDifferenceFrom() <= diff && diff <= r.ageDifferenceTo()).count();
                    if (hits > 1) {
                        fail("Form " + code + " has overlapping age-difference bands at age " + a);
                    }
                    if (hits == 0) {
                        fail("Form " + code + " has no rate for age " + a + " with an age difference of " + diff);
                    }
                }
            }
        }
    }

    private static void checkFrequencies(List<AnnuityFrequencyFactor> frequencies) {
        Set<String> seen = new HashSet<>();
        for (AnnuityFrequencyFactor f : frequencies) {
            if (!AnnuityFrequencies.isKnown(f.frequency())) {
                fail(f.frequency() + " is not an annuity payment frequency (MONTHLY, QUARTERLY, SEMI_ANNUAL or ANNUAL)");
            }
            if (!seen.add(f.frequency())) {
                fail("Frequency " + f.frequency() + " appears more than once");
            }
            if (f.factor() == null || f.factor().signum() <= 0 || f.factor().compareTo(BigDecimal.ONE) > 0) {
                fail("A frequency factor must be greater than 0 and at most 1");
            }
            if ("ANNUAL".equals(f.frequency()) && f.factor().compareTo(BigDecimal.ONE) != 0) {
                fail("The ANNUAL frequency factor is 1");
            }
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
