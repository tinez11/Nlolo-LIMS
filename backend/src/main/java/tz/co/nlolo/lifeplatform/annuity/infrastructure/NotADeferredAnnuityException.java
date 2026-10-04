package tz.co.nlolo.lifeplatform.annuity.infrastructure;

/** The policy is not a deferred annuity -- the console reads it as "no vesting panel". A 404. */
class NotADeferredAnnuityException extends RuntimeException {
    NotADeferredAnnuityException(String policyNumber) {
        super("Policy " + policyNumber + " is not a deferred annuity");
    }
}
