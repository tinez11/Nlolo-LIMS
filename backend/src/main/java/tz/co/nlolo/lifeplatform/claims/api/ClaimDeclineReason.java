package tz.co.nlolo.lifeplatform.claims.api;

/**
 * A policy-term reason a claim was declined, as distinct from the assessor's prose.
 *
 * <p>Each value names a window the platform can compute from dates. The platform does not
 * decide that any of them applies — it cannot: {@code causeOfDeath} is free text and a
 * credit-life borrower has no health record, because nobody below the free cover limit is
 * underwritten. What the platform does is say which reasons are AVAILABLE on the date of
 * event, refuse one that is not, and record which was used.
 *
 * <p>Null on every ordinary decline — fraud, non-disclosure, an event outside cover. Those are
 * the assessor's findings alone and need no window.
 */
public enum ClaimDeclineReason {

    /**
     * Death by suicide inside the product's suicide exclusion window.
     *
     * <p>The classic anti-selection control, and on credit life close to the only one: a
     * borrower who takes a loan intending not to survive it would otherwise transfer the whole
     * debt to the insurer for one premium.
     */
    SUICIDE_WITHIN_EXCLUSION,

    /**
     * Death from a condition the borrower already had when cover started, inside the
     * pre-existing exclusion window.
     *
     * <p>Only ever an assessor's finding from evidence gathered at claim. Nothing is known
     * about a borrower's health at enrolment — the lender's file carries a name, a date of
     * birth and a loan.
     */
    PRE_EXISTING_WITHIN_EXCLUSION
}
