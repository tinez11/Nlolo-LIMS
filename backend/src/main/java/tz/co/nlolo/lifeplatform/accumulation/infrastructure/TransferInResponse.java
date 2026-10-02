package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.TransferInView;

public record TransferInResponse(String transferId, String policyNumber, MoneyResponse amount, String sourceScheme,
                                 String documentRef, String recordedBy, String recordedAt) {
    static TransferInResponse from(TransferInView t) {
        return new TransferInResponse(t.transferId().toString(), t.policyNumber(), MoneyResponse.of(t.amount(), t.currency()),
            t.sourceScheme(), t.documentRef(), t.recordedBy(), t.recordedAt().toString());
    }
}
