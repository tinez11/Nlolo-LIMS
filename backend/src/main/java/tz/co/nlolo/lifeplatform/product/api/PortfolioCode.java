package tz.co.nlolo.lifeplatform.product.api;

/**
 * The IFRS 17 portfolio a product belongs to (IFRS 17 spec §6): contracts subject to similar risks and managed
 * together. With the cohort and the version's expected profitability it decides a policy's group of contracts, and the
 * accounting policy register keys the measurement model by it.
 */
public enum PortfolioCode {
    /** Term life and single credit life. */
    TERM,
    /** Whole life. */
    WL,
    /** Endowment. */
    END,
    /** Money-back. */
    MB,
    /** With-profits (participating). */
    PAR,
    /** Unit-linked. */
    ULIP,
    /** Savings accounts. */
    SAV,
    /** Fixed-term deposits. */
    DEP,
    /** Immediate annuity. */
    IANN,
    /** Deferred annuity. */
    DANN,
    /** Pension (deferred, vesting into an annuity). */
    PEN,
    /** Group life. */
    GRPL,
    /** Credit life. */
    CRL,
    /** Family funeral. */
    FUN;

    /**
     * The portfolio a product of this category takes when its creator names none -- the category's plain reading.
     * A product whose versions make it something narrower (with-profits, money-back, savings, a pension) names its
     * portfolio when it is created.
     */
    public static PortfolioCode defaultFor(ProductCategory category) {
        return switch (category) {
            case TERM_LIFE -> TERM;
            case ENDOWMENT, EDUCATION_SAVINGS -> END;
            case WHOLE_LIFE -> WL;
            case ANNUITY -> IANN;
            case UNIT_LINKED -> ULIP;
            case GROUP_LIFE -> GRPL;
            case CREDIT_LIFE -> CRL;
            case FUNERAL -> FUN;
        };
    }
}
