package tz.co.nlolo.lifeplatform.finaccounting.api;

/** No accounting policy election with that id for the caller's tenant (a 404). */
public class PolicyElectionNotFoundException extends RuntimeException {
    public PolicyElectionNotFoundException(String message) {
        super(message);
    }
}
