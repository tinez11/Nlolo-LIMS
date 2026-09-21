package tz.co.nlolo.lifeplatform.policy.api;

/**
 * How a lender charges interest, which decides how fast the principal actually falls.
 *
 * <p>Configured once per scheme, from the lending agreement. It was originally to be
 * INFERRED from each borrower's stated instalment, but neither real client schedule
 * carries an instalment or even an interest rate, so there is nothing to infer from -- see
 * docs/superpowers/plans/2026-09-21-credit-life-1-the-contract.md.
 */
public enum InterestMethod {

    /** Interest on the outstanding balance. The standard annuity. */
    REDUCING_BALANCE,

    /**
     * Interest on the ORIGINAL principal for the whole term, common in Tanzanian lending.
     *
     * <p>Principal then repays in equal slices, so the outstanding principal falls in a
     * straight line -- and that decline needs no interest rate at all, which is what makes
     * it computable from client files that state none.
     */
    FLAT_RATE
}
