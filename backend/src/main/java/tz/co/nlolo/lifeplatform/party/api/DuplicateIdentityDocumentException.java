package tz.co.nlolo.lifeplatform.party.api;

/**
 * One identity document registers one person per tenant.
 *
 * <p>Mirrors {@link DuplicateRegistrationNumberException} for corporates, and
 * matters more: a KYC register in which the same national ID can be registered
 * twice cannot do the job it exists for.
 *
 * <p>The message names the type but NOT the number. The number is a
 * government-issued identifier belonging to a person who may not be the caller,
 * and error text reaches logs, traces and screens; "a party with this NATIONAL_ID
 * already exists" tells the operator everything they need to act on without
 * echoing the document back.
 */
public class DuplicateIdentityDocumentException extends RuntimeException {
    public DuplicateIdentityDocumentException(IdType idType) {
        super("A party with this " + idType + " is already registered for this tenant");
    }
}
