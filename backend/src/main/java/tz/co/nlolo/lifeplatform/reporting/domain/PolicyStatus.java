package tz.co.nlolo.lifeplatform.reporting.domain;

/** The statuses a policy's history can move through. SUSPENDED and PAID_UP still count as in force: a
 * suspended policy has not lapsed (regreporting made the same decision), and a paid-up one is still on cover. */
public enum PolicyStatus {
    ACTIVE(true), SUSPENDED(true), PAID_UP(true),
    LAPSED(false), MATURED(false), EXPIRED(false), CLAIM_TERMINATED(false), SURRENDERED(false),
    CANCELLED_FREE_LOOK(false), ENDED(false);

    private final boolean inForce;

    PolicyStatus(boolean inForce) {
        this.inForce = inForce;
    }

    public boolean inForce() {
        return inForce;
    }
}
