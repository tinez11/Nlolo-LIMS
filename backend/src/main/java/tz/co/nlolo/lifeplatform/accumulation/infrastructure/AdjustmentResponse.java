package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.AdjustmentView;

/** An adjustment's amount is signed: a negative one takes money out of the account. */
public record AdjustmentResponse(String adjustmentId, String policyNumber, String amount, String reason, String status,
                                 String proposedBy, String proposedAt, String decidedBy, String decidedAt) {
    static AdjustmentResponse from(AdjustmentView a) {
        return new AdjustmentResponse(a.adjustmentId().toString(), a.policyNumber(), a.amount().toPlainString(), a.reason(),
            a.status(), a.proposedBy(), a.proposedAt().toString(), a.decidedBy(),
            a.decidedAt() != null ? a.decidedAt().toString() : null);
    }
}
