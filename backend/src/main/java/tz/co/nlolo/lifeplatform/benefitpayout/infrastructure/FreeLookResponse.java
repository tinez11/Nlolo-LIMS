package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import tz.co.nlolo.lifeplatform.benefitpayout.api.FreeLookCancellationView;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The wire shape of a free-look cancellation. Every figure is {@code {amount, currencyCode}}. */
public record FreeLookResponse(UUID cancellationId, String policyNumber, String status,
                               Map<String, String> premiumsCollected, Map<String, String> refundAmount,
                               String payeeRef, String requestedBy, String approvedBy,
                               List<Deduction> deductions) {

    public record Deduction(String description, Map<String, String> amount, UUID documentId) {}

    public static FreeLookResponse from(FreeLookCancellationView v) {
        return new FreeLookResponse(v.cancellationId(), v.policyNumber(), v.status(),
            PayoutInstalmentResponse.money(v.premiumsCollected(), v.currency()),
            PayoutInstalmentResponse.money(v.refundAmount(), v.currency()),
            v.payeeRef(), v.requestedBy(), v.approvedBy(),
            v.deductions().stream()
                .map(d -> new Deduction(d.description(),
                    PayoutInstalmentResponse.money(d.amount(), v.currency()), d.documentId()))
                .toList());
    }
}
