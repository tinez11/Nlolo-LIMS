package tz.co.nlolo.lifeplatform.policy.api;

/**
 * How a group scheme arrives at each member's benefit.
 *
 * <p>One basis per scheme, configured rather than hard-coded, because real schemes
 * genuinely differ: {@code FLAT} is typical for SACCO, funeral and credit-linked cover,
 * {@code SALARY_MULTIPLE} is the standard for formal employer schemes, and {@code GRADED}
 * splits benefit by staff category.
 *
 * <p>Whichever basis applies, the resulting amount is <b>stored on the member</b>, never
 * recomputed on read. A claim has to pay the benefit that was in force on the date of
 * event, and a salary-multiple benefit recomputed today would value a two-year-old death
 * at a salary the member did not have when they died.
 */
public enum BenefitBasis {
    /** Every member gets the same amount, set on the scheme. */
    FLAT,
    /** Benefit is the member's salary times the scheme's multiple. */
    SALARY_MULTIPLE,
    /** Benefit comes from a per-grade table on the scheme. */
    GRADED,

    /**
     * Benefit is the outstanding principal of the member's own loan, falling as it is
     * repaid. Credit life, and the only basis whose amount changes with the date.
     *
     * <p>Carries no scheme-level parameter, exactly as {@code GRADED} does not: the
     * amount is on the member, because every borrower's loan is different.
     *
     * <p>The sentence above about never recomputing on read still holds, and is the
     * reason this is not simply a moving number. What is stored on the member is cover
     * AT INCEPTION -- the principal, capped at the free cover limit. The decline from
     * there is recomputed by {@code AmortisationCalculator} as at the date of event, and
     * the stored figure is its ceiling. Materialising one row per repayment date would be
     * tens of thousands of rows per enrolment file.
     */
    AMORTISING_LOAN
}
