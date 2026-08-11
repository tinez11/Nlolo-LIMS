package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.api.PayoutBatchView;

import java.util.UUID;

public record PayoutBatchResponseDto(UUID batchId, String batchType, String status,
                                      int disbursementCount, int failedCount) {

    public static PayoutBatchResponseDto from(PayoutBatchView view) {
        return new PayoutBatchResponseDto(view.batchId(), view.batchType(), view.status(),
            view.disbursementCount(), view.failedCount());
    }
}
