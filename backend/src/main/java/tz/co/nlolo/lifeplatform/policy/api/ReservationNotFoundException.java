package tz.co.nlolo.lifeplatform.policy.api;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(UUID reservationId) {
        super("No loan value reservation found for id " + reservationId);
    }
}
