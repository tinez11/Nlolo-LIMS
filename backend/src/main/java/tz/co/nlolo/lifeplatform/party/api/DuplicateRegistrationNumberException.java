package tz.co.nlolo.lifeplatform.party.api;

public class DuplicateRegistrationNumberException extends RuntimeException {
    public DuplicateRegistrationNumberException(String registrationNumber) {
        super("A corporate party with registration number " + registrationNumber + " already exists for this tenant");
    }
}
