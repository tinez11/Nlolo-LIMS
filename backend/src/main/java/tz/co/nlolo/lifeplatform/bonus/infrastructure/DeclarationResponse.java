package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import tz.co.nlolo.lifeplatform.bonus.api.BonusDeclarationView;

/**
 * Rates as plain strings without trailing zeros -- accumulation's RateDeclarationResponse, exactly:
 * NUMERIC(7,4) reads back as 3.5000, and the console would otherwise show "3.5000%".
 */
public record DeclarationResponse(String declarationId, String productId, String valuationDate,
                                  String reversionaryRatePercent, String terminalRatePercent, String status,
                                  String proposedBy, String proposedAt, String approvedBy, String approvedAt,
                                  String completedAt) {
    public static DeclarationResponse from(BonusDeclarationView v) {
        return new DeclarationResponse(v.declarationId().toString(), v.productId().toString(), v.valuationDate().toString(),
            v.reversionaryRatePercent().stripTrailingZeros().toPlainString(),
            v.terminalRatePercent().stripTrailingZeros().toPlainString(), v.status().name(), v.proposedBy(),
            v.proposedAt().toString(), v.approvedBy(), v.approvedAt() != null ? v.approvedAt().toString() : null,
            v.completedAt() != null ? v.completedAt().toString() : null);
    }
}
