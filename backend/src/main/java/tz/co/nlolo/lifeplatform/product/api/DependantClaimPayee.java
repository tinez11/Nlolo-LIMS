package tz.co.nlolo.lifeplatform.product.api;

/** Who is paid when a dependant (anyone but the main member) dies. */
public enum DependantClaimPayee {
    /** The main member, who owns the policy. */
    MAIN_MEMBER,
    /** A beneficiary the main member nominated on the policy. */
    MAIN_MEMBER_BENEFICIARY
}
