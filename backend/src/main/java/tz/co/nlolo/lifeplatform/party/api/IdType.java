package tz.co.nlolo.lifeplatform.party.api;

/**
 * The kind of identity document on record for an individual.
 *
 * <p>The four documents a Tanzanian insurer will actually be handed. This is a
 * closed set rather than free text because it pairs with a uniqueness rule --
 * {@code ux_party_individual_identity} is keyed on {@code (tenant_id, id_type,
 * id_number)}, and a free-text type would let "National ID" and "NIDA" describe
 * the same document and register the same person twice.
 */
public enum IdType {
    NATIONAL_ID,
    PASSPORT,
    DRIVING_LICENCE,
    VOTER_ID
}
