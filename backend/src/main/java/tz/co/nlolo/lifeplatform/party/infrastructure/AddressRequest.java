package tz.co.nlolo.lifeplatform.party.infrastructure;

import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.party.api.Address;

/**
 * The wire shape of an address on {@code POST /parties/individuals}.
 *
 * <p>Separate from {@link Address} (the api-layer value object) for the same reason
 * {@link ContactInfo} is separate from its fields on the domain call: this one carries
 * Bean Validation constraints mirroring openapi-party.yaml, and the api record is what
 * other modules would read. Every field is optional -- an address is frequently partial
 * at registration.
 */
public record AddressRequest(
    @Size(max = 255) String line,
    @Size(max = 100) String ward,
    @Size(max = 100) String district,
    @Size(max = 100) String region,
    @Size(max = 20) String postalCode) {

    public Address toAddress() {
        return new Address(line, ward, district, region, postalCode);
    }
}
