package tz.co.nlolo.lifeplatform.product.infrastructure;

import java.util.UUID;

/** A unit-linked read for a version that is not unit-linked: a 404 with its own errorCode. */
class NotAUnitLinkedProductException extends RuntimeException {
    NotAUnitLinkedProductException(UUID versionId) {
        super("Product version " + versionId + " is not a unit-linked product");
    }
}
