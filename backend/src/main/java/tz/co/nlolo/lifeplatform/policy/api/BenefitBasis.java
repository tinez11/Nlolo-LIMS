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
    GRADED
}
