package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Where a group member stands against their scheme's free cover limit.
 *
 * <p>Deliberately four states rather than a boolean. {@link #EVIDENCE_REQUIRED} and
 * {@link #DECLINED} produce the <i>same covered amount</i> — the FCL — and mean entirely
 * different things: one is a decision outstanding, the other a decision made. A claims
 * assessor looking at a member who died with cover capped at the limit has to be able to
 * tell whether the excess was still being underwritten or had been refused.
 */
public enum MemberUnderwritingStatus {
    /** Benefit is at or under the free cover limit; no medical evidence is needed. */
    WITHIN_FCL,
    /**
     * Benefit exceeds the limit and evidence is outstanding.
     *
     * <p>The member is <b>covered up to the limit meanwhile</b> — the market-standard
     * rule, confirmed with the client: cover at the FCL is immediate and only the excess
     * waits on acceptance.
     */
    EVIDENCE_REQUIRED,
    /** Evidence was provided and the excess granted; the full benefit is now in force. */
    ACCEPTED,
    /** Evidence was provided and the excess refused; cover stays at the limit. */
    DECLINED
}
