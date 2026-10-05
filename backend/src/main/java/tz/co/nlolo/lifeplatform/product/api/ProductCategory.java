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
    CREDIT_LIFE,

    /**
     * Family funeral cover: one policy, owned by a main member, covering them and their family --
     * each life with its own benefit from the chosen plan and its own premium from its role and age
     * band. Protection, beside TERM_LIFE and not folded into it, for CREDIT_LIFE's reason: a category
     * gates behaviour across modules.
     *
     * <p>See docs/superpowers/specs/2026-10-04-family-funeral-cover-design.md.
     */
    FUNERAL
}
