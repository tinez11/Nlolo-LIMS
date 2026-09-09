package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Why a policy was issued by hand instead of through the normal decision path.
 *
 * <p>Required on {@code POST /policies/manual-issue}, and the value decides whether cover starts
 * immediately or waits for the first premium like any other new business.
 *
 * <p>That distinction is the whole point of the enum. "Manual issue always starts cover" would
 * make the exception path a way to skip the money rule for ordinary business — the same shape as
 * the defect that let manual issue duplicate a policy in the first place. The three bases that
 * start cover are the three where cover genuinely already exists somewhere else; the two that do
 * not are new business wearing an exception's clothes.
 *
 * <p>ACORD types the bypass on the contract rather than omitting the record, and this is the
 * platform's version of that: an auditor asking "on what basis did cover start before the
 * premium?" gets a field rather than a paragraph of free text.
 */
public enum IssuanceBasis {

    /** Brought in from another administration system, in force there and paid for years. */
    MIGRATION(true),

    /** Continuous cover from a converted policy; a gap would be a real lapse in someone's life assurance. */
    CONVERSION(true),

    /** Follows arrears being settled, so the money has already arrived. */
    REINSTATEMENT(true),

    /** A manual review overturning an automated block. Changes WHO may be covered, not whether they pay. */
    UNDERWRITING_OVERRIDE(false),

    /** Guaranteed acceptance waives evidence of insurability, not the premium. */
    GUARANTEED_ISSUE(false);

    private final boolean startsCoverImmediately;

    IssuanceBasis(boolean startsCoverImmediately) {
        this.startsCoverImmediately = startsCoverImmediately;
    }

    public boolean startsCoverImmediately() {
        return startsCoverImmediately;
    }
}
