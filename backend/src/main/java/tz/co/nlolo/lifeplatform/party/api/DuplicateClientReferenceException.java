package tz.co.nlolo.lifeplatform.party.api;

/**
 * The insurer's own reference for a client is already on another party in this tenant.
 *
 * <p>Exists for the reason {@link DuplicateRegistrationNumberException} does: until 2026-10-14
 * this collision was reported as {@link DuplicateIdentityDocumentException}, so registering a
 * client whose CLIENT REFERENCE was taken was refused with "A party with this PASSPORT is already
 * registered for this tenant". Correcting the passport changed nothing, because the passport was
 * never the problem — the only way out was to guess which other field the message meant.
 *
 * <p>{@code ux_party_client_reference} is a partial unique index on
 * {@code (tenant_id, client_reference) WHERE client_reference IS NOT NULL}: the reference is
 * optional, and two clients may both have none.
 */
public class DuplicateClientReferenceException extends RuntimeException {

    public DuplicateClientReferenceException(String clientReference) {
        super("Client reference " + clientReference + " already belongs to another client in this tenant");
    }
}
