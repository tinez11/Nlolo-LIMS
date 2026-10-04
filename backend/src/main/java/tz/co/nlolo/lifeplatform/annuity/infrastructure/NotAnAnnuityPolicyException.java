package tz.co.nlolo.lifeplatform.annuity.infrastructure;

/** An annuity read for a policy that is not one: a 404 the console reads as "no Annuity tab". */
class NotAnAnnuityPolicyException extends RuntimeException {
    NotAnAnnuityPolicyException(String policyNumber) {
        super("Policy " + policyNumber + " is not an annuity");
    }
}
