package tz.co.nlolo.lifeplatform.product.api;

public enum ProductCategory {
    TERM_LIFE, ENDOWMENT, WHOLE_LIFE, ANNUITY, UNIT_LINKED, GROUP_LIFE, EDUCATION_SAVINGS,

    /**
     * Lender-driven cover on a borrower's life, paying what they still owe. §4 of the
     * client's underwriting requirements table.
     *
     * <p>Not folded into GROUP_LIFE, though it reuses the group tables: GROUP_LIFE already
     * gates eight behaviours, several of which are wrong for a lender scheme, and reusing
     * it would inherit them silently instead of forcing a decision at each one.
     *
     * <p>See docs/superpowers/specs/2026-09-21-credit-life-design.md.
     */
    CREDIT_LIFE
}
