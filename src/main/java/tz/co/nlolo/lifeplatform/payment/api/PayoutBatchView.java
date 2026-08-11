package tz.co.nlolo.lifeplatform.payment.api;

import java.util.UUID;

/** disbursementCount/failedCount are computed from the member instructions, never stored —
 * openapi-payment.yaml's PayoutBatchView declares both, and Deliverable 3 §7.3 requires batch
 * status to be derived from members rather than duplicated. */
public record PayoutBatchView(UUID batchId, String batchType, String status,
                               int disbursementCount, int failedCount) {}
