package tz.co.nlolo.lifeplatform.billing.api;

import java.util.UUID;

public class InvoiceNotFoundException extends RuntimeException {
    public InvoiceNotFoundException(UUID invoiceId) {
        super("Invoice " + invoiceId + " not found");
    }

    public InvoiceNotFoundException(String message) {
        super(message);
    }
}
