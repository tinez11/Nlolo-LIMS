package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.TopUpView;

public record TopUpResponse(String topUpId, String policyNumber, MoneyResponse amount, String payerRef, String status,
                            String requestedBy, String requestedAt) {
    static TopUpResponse from(TopUpView t) {
        return new TopUpResponse(t.topUpId().toString(), t.policyNumber(), MoneyResponse.of(t.amount(), t.currency()),
            t.payerRef(), t.status(), t.requestedBy(), t.requestedAt().toString());
    }
}
