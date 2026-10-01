package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.RateDeclarationView;

public record RateDeclarationResponse(String declarationId, String productId, String ratePercent, String effectiveFrom,
                                      String status, String proposedBy, String proposedAt, String approvedBy,
                                      String approvedAt) {
    static RateDeclarationResponse from(RateDeclarationView v) {
        return new RateDeclarationResponse(v.declarationId().toString(), v.productId().toString(),
            // stripTrailingZeros: the column is NUMERIC(7,4), so the same 6.5% would otherwise read
            // "6.5" from the proposal and "6.5000" once reloaded -- one rate, two spellings.
            v.ratePercent().stripTrailingZeros().toPlainString(), v.effectiveFrom().toString(), v.status().name(), v.proposedBy(),
            v.proposedAt().toString(), v.approvedBy(), v.approvedAt() != null ? v.approvedAt().toString() : null);
    }
}
