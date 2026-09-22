package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Why a row of a lender's schedule was refused.
 *
 * <p>A closed set, because these reach a counterparty. A lender fixing a spreadsheet
 * needs to be able to sort, count and filter their own rejection report, and free text
 * cannot be sorted, counted or filtered. The prose reason beside the code carries the
 * specifics.
 */
public enum EnrolmentRejection {

    /** A column the template requires was blank. */
    MISSING_REQUIRED_FIELD,

    /**
     * A value was present and unreadable. The commonest causes are both Excel's:
     * an amount exported as {@code 8.5E+06}, and a date exported as the serial
     * {@code 46203}.
     */
    MALFORMED_VALUE,

    /** The same loan appears twice in one file, or is already on the scheme. */
    DUPLICATE_LOAN_ACCOUNT_NUMBER,

    /** Cover cannot start before the loan exists. */
    DISBURSEMENT_DATE_IN_FUTURE,

    /** Entry age, maturity age or term falls outside the product's own bounds. */
    ENTRY_AGE_OR_TERM_OUT_OF_BOUNDS,

    /** Paid out before this contract existed, so it is risk the scheme never priced. */
    LOAN_BEFORE_SCHEME_COMMENCED,

    /** Already an active member of this scheme, from an earlier accepted file. */
    ALREADY_ENROLLED
}
