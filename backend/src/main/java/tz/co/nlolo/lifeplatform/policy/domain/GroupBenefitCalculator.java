package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Values a group member's benefit and tests it against the scheme's free cover limit.
 *
 * <p>A pure function over plain values, deliberately: this is the only place on the
 * platform that decides what a group member is insured for, and it must be exercisable
 * against fixtures with no database and no Spring context — the same reasoning that put
 * the claim and issue rules in {@code src/gates}.
 *
 * <p><b>Nothing here is recomputed on read.</b> The result is stored on the member's
 * effective-dated benefit row, because a claim pays the benefit in force on the date of
 * event, not the benefit today's inputs would produce.
 */
public final class GroupBenefitCalculator {

    private GroupBenefitCalculator() {}

    /**
     * What a member is worth, and what they are actually covered for.
     *
     * @param benefitAmount the basis result — see {@link #benefitFor}
     * @param fclAmount the scheme's free cover limit, or null when the scheme has none
     */
    public record Valuation(BigDecimal benefitAmount, BigDecimal coveredAmount,
                            MemberUnderwritingStatus underwritingStatus) {}

    /**
     * Apply the scheme's basis to one member.
     *
     * @throws IllegalArgumentException when the basis and its inputs disagree — a
     *     salary-multiple scheme with no salary cannot value anybody, and guessing would
     *     put a fabricated death benefit on a real contract.
     */
    public static BigDecimal benefitFor(BenefitBasis basis, BigDecimal flatBenefitAmount,
                                         BigDecimal salaryMultiple, BigDecimal memberSalary,
                                         BigDecimal gradeBenefitAmount,
                                         BigDecimal loanPrincipalAmount) {
        return switch (basis) {
            case FLAT -> require(flatBenefitAmount, "This scheme is flat-benefit but carries no amount");
            case SALARY_MULTIPLE -> {
                BigDecimal multiple = require(salaryMultiple, "This scheme is salary-multiple but carries no multiple");
                BigDecimal salary = require(memberSalary, "A salary-multiple scheme needs the member's salary");
                // Rounded once, at the end, HALF_UP to 2dp -- the same rule quotePremium
                // follows. A 3.5x multiple on an odd salary otherwise carries fractions of
                // a cent into a sum assured.
                yield salary.multiply(multiple).setScale(2, RoundingMode.HALF_UP);
            }
            case GRADED -> require(gradeBenefitAmount,
                "This member's grade is not on the scheme's grade table");
            // Cover AT INCEPTION. The decline is not decided here: AmortisationCalculator
            // recomputes it as at the date of event, capped by whatever this produces
            // once evaluate() has applied the free cover limit.
            case AMORTISING_LOAN -> require(loanPrincipalAmount,
                "A credit-life member needs the principal of their own loan");
            // A family's cover is its lives' plan benefits, summed when the family joins; never computed here.
            case FUNERAL_PLAN -> throw new IllegalArgumentException(
                "A group funeral member's cover comes from their family's plan benefits");
        };
    }

    /**
     * Test a benefit against the free cover limit.
     *
     * <p>The rule, confirmed with the client and standard in the market: a member above
     * the limit is <b>covered up to it immediately</b>, and the excess is granted only on
     * acceptance. So a 150m benefit against a 100m FCL is 100m of real cover from day one
     * — not zero, and not 150m.
     *
     * <p>A null {@code fclAmount} means the scheme has no limit, which is a real design
     * (small flat schemes commonly have none) and is <b>not</b> the same as a limit of
     * zero, which would send every member to underwriting.
     */
    public static Valuation evaluate(BigDecimal benefitAmount, BigDecimal fclAmount) {
        if (benefitAmount == null || benefitAmount.signum() <= 0) {
            throw new IllegalArgumentException("A member's benefit must be a positive amount");
        }
        if (fclAmount == null || benefitAmount.compareTo(fclAmount) <= 0) {
            return new Valuation(benefitAmount, benefitAmount, MemberUnderwritingStatus.WITHIN_FCL);
        }
        return new Valuation(benefitAmount, fclAmount, MemberUnderwritingStatus.EVIDENCE_REQUIRED);
    }

    /**
     * The covered amount once an underwriting decision on the excess has been made.
     *
     * <p>Accepted grants the full benefit. Declined leaves cover at the limit — the member
     * is not uninsured, they are insured for what the scheme gives without evidence.
     */
    public static BigDecimal coveredAfterDecision(BigDecimal benefitAmount, BigDecimal fclAmount,
                                                   boolean accepted) {
        if (accepted) return benefitAmount;
        return fclAmount != null ? fclAmount : benefitAmount;
    }

    private static BigDecimal require(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
