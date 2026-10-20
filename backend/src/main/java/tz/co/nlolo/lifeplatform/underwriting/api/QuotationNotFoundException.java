package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.UUID;

/**
 * No such quotation for this caller: absent, another tenant's, or another agent's -- one answer for all three, so an
 * agent cannot tell another agent's quotation from none (2026-10-20).
 */
public class QuotationNotFoundException extends RuntimeException {
    public QuotationNotFoundException(UUID quotationId) {
        super("No quotation " + quotationId);
    }
}
