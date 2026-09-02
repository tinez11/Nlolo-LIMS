package tz.co.nlolo.lifeplatform.party.api;

/**
 * An identity document: a type and the number printed on it.
 *
 * <p>Both or neither, enforced here and again by {@code
 * party_identity_document_complete} in the schema. A number with no type cannot be
 * read back reliably, and a type with no number is noise.
 */
public record IdentityDocument(IdType type, String number) {

    public IdentityDocument {
        boolean hasType = type != null;
        boolean hasNumber = number != null && !number.isBlank();
        if (hasType != hasNumber) {
            throw new IllegalArgumentException(
                "An identity document needs both a type and a number, or neither");
        }
        number = hasNumber ? number.trim() : null;
    }

    /** Absent -- no identity document was recorded. */
    public static IdentityDocument none() {
        return new IdentityDocument(null, null);
    }

    /**
     * Deliberately NOT named {@code isPresent()}. This record goes on the wire inside
     * {@link PartyDetailView}, and Jackson treats a public no-arg {@code isX()} on a
     * record as an extra property -- which serialised a phantom {@code "present": false}
     * into the response and failed contract validation against the declared schema.
     * A {@code @JsonIgnore} would fix it too, at the cost of importing Jackson into an
     * api package that has no other reason to know about it.
     */
    public boolean recorded() {
        return type != null;
    }
}
