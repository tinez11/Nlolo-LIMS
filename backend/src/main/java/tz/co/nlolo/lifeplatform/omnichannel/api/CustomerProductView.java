package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The customer portal's product shelf (2026-10-08, the customer portal design step 5; PRD §13-16): what is offered
 * online, an indicative price, and what the customer asked for.
 *
 * @param quotable whether the portal can price it from a sum assured; otherwise an adviser prices it after a request
 */
public record CustomerProductView(UUID productId, String productName, String category, String currency, String summary,
                                  List<String> benefits, boolean quotable) {

    /** What a customer asks to be priced. termYears is optional; the product's own term applies without it. */
    public record QuoteRequest(BigDecimal sumAssured, String frequency, Integer termYears) {}

    /**
     * An indicative price, on the customer's own age, sex and smoker status as we hold them -- not an offer: underwriting
     * may load or decline.
     */
    public record Quote(UUID productId, String productName, String currency, BigDecimal sumAssured, String frequency,
                        BigDecimal instalment, BigDecimal yearly, int ageAtEntry) {}

    /** Asking for a product: it opens an application (an underwriting case) with the customer as applicant. */
    public record ApplicationRequest(UUID productId, BigDecimal sumAssured, String frequency, Integer termYears) {}

    /** One application as the customer reads it. */
    public record Application(UUID caseId, String proposalNumber, UUID productId, String productName, BigDecimal sumAssured,
                              String currency, String status, String statusText) {}
}
