package tz.co.nlolo.lifeplatform.product.api;

import java.util.UUID;

/** No such account charge in this tenant -- including another tenant's, which is the same 404. */
public class AccountChargeNotFoundException extends RuntimeException {
    public AccountChargeNotFoundException(UUID chargeId) {
        super("No account charge " + chargeId);
    }
}
