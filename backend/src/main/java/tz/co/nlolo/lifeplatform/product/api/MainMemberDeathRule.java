package tz.co.nlolo.lifeplatform.product.api;

/** What becomes of a funeral policy when its main member dies. */
public enum MainMemberDeathRule {
    /** Every other life's cover ends with the policy (or at the paid-to date, with free cover). */
    POLICY_ENDS,
    /** The spouse becomes the policyholder and main member, and the policy carries on. */
    SPOUSE_TAKES_OVER
}
