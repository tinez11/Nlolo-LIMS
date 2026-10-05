package tz.co.nlolo.lifeplatform.product.infrastructure;

import java.util.UUID;

/** A funeral read for a version that is not a funeral plan: a 404 with its own errorCode. */
class NotAFuneralProductException extends RuntimeException {
    NotAFuneralProductException(UUID versionId) {
        super("Product version " + versionId + " is not a funeral plan");
    }
}
