package tz.co.nlolo.lifeplatform.reinsurance.api;

/**
 * Where a treaty stands on a given day, worked out from its dates -- what staff mean by "active".
 *
 * <p>{@link TreatyStatus} is the stored flag, and it cannot answer this: nothing on the platform ever
 * moves a treaty to {@code EXPIRED}, so a treaty showed "Active" a week before it started and would
 * have gone on showing it for ever after it ended. Ceding was never affected -- selection already
 * checks the dates through {@code isActiveOn} -- but the treaty list told staff something false.
 * This is read, never stored, so it cannot go stale.
 */
public enum TreatyStanding {
    /** Its effective-from is still to come. */
    NOT_STARTED,
    /** On cover today: started, not ended, not withdrawn. */
    IN_FORCE,
    /** Past its effective-to, or withdrawn ({@link TreatyStatus#EXPIRED}). */
    ENDED,
    /** Proposed, waiting for a second person (2026-11-12, finding 170). */
    AWAITING_APPROVAL,
    /** Rejected or withdrawn before it ever ceded. */
    REJECTED
}
