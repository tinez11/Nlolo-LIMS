package tz.co.nlolo.lifeplatform.bonus.infrastructure;

/** A bonus read for a policy that is not with-profits: a 404, which the console reads as "no Bonuses tab". */
class NotWithProfitsException extends RuntimeException {
    NotWithProfitsException(String policyNumber) {
        super("Policy " + policyNumber + " is not with-profits");
    }
}
