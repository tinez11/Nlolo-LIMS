package tz.co.nlolo.lifeplatform.payment.domain;

import java.math.BigDecimal;

/**
 * The Anti-Corruption Layer boundary named in docs/01-domain-map.md:137 ("Gateway-specific
 * adapters behind PaymentGatewayPort"). External gateway payload shapes never cross this
 * interface -- everything is translated to these records and to GatewayResult, so a second
 * aggregator (or a real one replacing the WireMock stub) is a new adapter and nothing else.
 * Structural precedent: underwriting.domain.RulesEnginePort.
 */
public interface PaymentGatewayPort {

    record GatewayDisbursementRequest(String payeeRef, BigDecimal amount, String currency, String reference) {}

    record GatewayCollectionRequest(String payerRef, BigDecimal amount, String currency, String reference) {}

    /**
     * @param accepted whether the rail accepted the instruction
     * @param gatewayReference the rail's own reference; may be null when it rejected outright
     * @param failureReason human-readable, null when accepted. Never carries a raw gateway
     *                      payload -- that would leak the external shape past the ACL.
     */
    record GatewayResult(boolean accepted, String gatewayReference, String failureReason) {}

    GatewayResult submitDisbursement(GatewayDisbursementRequest request);

    GatewayResult submitCollection(GatewayCollectionRequest request);
}
