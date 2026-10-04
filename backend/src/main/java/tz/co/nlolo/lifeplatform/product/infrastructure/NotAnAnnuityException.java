package tz.co.nlolo.lifeplatform.product.infrastructure;

import java.util.UUID;

/** An annuity read for a version that is not an annuity: a 404 with its own errorCode. */
class NotAnAnnuityException extends RuntimeException {
    NotAnAnnuityException(UUID versionId) {
        super("Product version " + versionId + " is not an annuity");
    }
}
