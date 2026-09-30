package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

/** No surrender request with the given id in this tenant. Maps to 404. */
public class SurrenderRequestNotFoundException extends RuntimeException {
    public SurrenderRequestNotFoundException(UUID surrenderRequestId) {
        super("No surrender request " + surrenderRequestId);
    }
}
