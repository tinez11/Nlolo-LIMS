package tz.co.nlolo.lifeplatform.party.api;

/**
 * A postal or residential address, shaped for Tanzanian addressing.
 *
 * <p>One address per party. A party with several (residential, postal, employer)
 * is a real future need and a separate table when it arrives; five nullable
 * fields now beat a premature join.
 *
 * <p>Every field is optional: an address is frequently partial at registration,
 * and a half-known address is worth more than none.
 */
public record Address(String line, String ward, String district, String region, String postalCode) {

    /** Absent -- no address was recorded. */
    public static Address none() {
        return new Address(null, null, null, null, null);
    }

    /** Named {@code recorded()} rather than {@code isPresent()} for the reason given on
     *  {@link IdentityDocument#recorded()}: an {@code isX()} on a record serialises as an
     *  extra JSON property. */
    public boolean recorded() {
        return line != null || ward != null || district != null || region != null || postalCode != null;
    }
}
