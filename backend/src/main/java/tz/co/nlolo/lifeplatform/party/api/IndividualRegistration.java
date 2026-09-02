package tz.co.nlolo.lifeplatform.party.api;

import java.time.LocalDate;

/**
 * Everything that can be recorded about a person at registration.
 *
 * <p>A parameter object rather than a widened parameter list.
 * {@code registerIndividual} had five flat parameters and roughly fifty call
 * sites, almost all of them test fixtures; growing that signature to seventeen
 * would have been a large merge surface for no reader benefit. The legacy flat
 * form survives as a {@code default} method on {@link PartyApi} that builds a
 * {@link #minimal} instance, so nothing had to change.
 *
 * <p>Only {@code fullName} and {@code dateOfBirth} are genuinely required. Every
 * other field is optional because registration happens in front of a person who
 * may not have the answer to hand -- an agent registering a walk-in should not be
 * blocked on an employer's name -- and a half-known record beats none.
 *
 * <p>{@code occupation} is what the applicant said they do. {@code
 * occupationClass} is the rating band an underwriter assigns, matching {@code
 * product.rating_table}'s {@code OCCUPATION_CLASS} factor. They are separate
 * fields because the platform does not own a canonical occupation list, so the
 * declared text is recorded as given and the classification stays a judgement
 * somebody makes and can be held to.
 */
public record IndividualRegistration(
    String fullName,
    LocalDate dateOfBirth,
    String phoneNumber,
    String email,
    Sex sex,
    SmokerStatus smokerStatus,
    IdentityDocument identityDocument,
    String occupation,
    String occupationClass,
    String employerName,
    String nationality,
    Address address) {

    public IndividualRegistration {
        // Never null downstream, so the entity and the mapper can read them without
        // a null check at every access.
        identityDocument = identityDocument != null ? identityDocument : IdentityDocument.none();
        address = address != null ? address : Address.none();
        nationality = nationality != null ? nationality.trim().toUpperCase() : null;
    }

    /**
     * The pre-Build-1 registration: a name, a date of birth and contact details.
     *
     * <p>What {@link PartyApi}'s legacy five-argument overload builds, and what the
     * customers realm still sends.
     */
    public static IndividualRegistration minimal(String fullName, LocalDate dateOfBirth,
                                                  String phoneNumber, String email) {
        return new IndividualRegistration(fullName, dateOfBirth, phoneNumber, email,
            null, null, IdentityDocument.none(), null, null, null, null, Address.none());
    }
}
