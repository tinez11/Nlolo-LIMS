package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.util.Map;
import java.util.UUID;

/** Wire view of a surrender request. quotedValue as amount + currencyCode, this layer's money shape. */
public record SurrenderRequestResponseDto(UUID surrenderRequestId, String policyNumber, String status,
                                          Map<String, String> quotedValue, String requestedBy, String approvedBy) {
    public static SurrenderRequestResponseDto from(PolicyApi.SurrenderRequestView v) {
        return new SurrenderRequestResponseDto(v.surrenderRequestId(), v.policyNumber(), v.status(),
            Map.of("amount", v.quotedValueAmount().toPlainString(), "currencyCode", v.quotedValueCurrency()),
            v.requestedBy(), v.approvedBy());
    }
}
