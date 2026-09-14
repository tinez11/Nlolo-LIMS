package tz.co.nlolo.lifeplatform.billing.api;

import java.util.UUID;

/**
 * Mapped to 404 Not Found -- no field receipt with this id in the caller's tenant.
 *
 * <p>Tenant-scoped lookup, so a receipt belonging to another tenant lands here rather than on a
 * 403. That is deliberate and matches {@link InvoiceNotFoundException}: telling a caller "this
 * exists but is not yours" confirms the id, which is the enumeration this platform closes
 * everywhere else.
 */
public class FieldReceiptNotFoundException extends RuntimeException {
    public FieldReceiptNotFoundException(UUID receiptId) {
        super("Field receipt " + receiptId + " not found");
    }
}
