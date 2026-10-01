package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.util.Map;
import java.util.UUID;

/** Wire view of a surrender request. quotedValue as amount + currencyCode, this layer's money shape. */
public record SurrenderRequestResponseDto(UUID surrenderRequestId, String policyNumber, String status,
                                          Map<String, String> quotedValue, String payeeRef,
                                          String requestedBy, String approvedBy) {
    public static SurrenderRequestResponseDto from(PolicyApi.SurrenderRequestView v) {
        return new SurrenderRequestResponseDto(v.surrenderRequestId(), v.policyNumber(), v.status(),
            Map.of("amount", v.quotedValueAmount().toPlainString(), "currencyCode", v.quotedValueCurrency()),
            // Where the money goes. Omitted until now, which left the approver -- the person whose
            // whole job is checking this payout before it leaves -- unable to see the destination.
            v.payeeRef(), v.requestedBy(), v.approvedBy());
    }
}
