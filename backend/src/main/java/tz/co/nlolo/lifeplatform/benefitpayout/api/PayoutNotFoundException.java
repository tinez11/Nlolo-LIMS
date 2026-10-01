package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.util.UUID;

/** No such payout record in this tenant. 404 -- and under RLS, another tenant's is equally absent. */
public class PayoutNotFoundException extends RuntimeException {
    public PayoutNotFoundException(UUID id) { super("No payout record " + id); }
}
