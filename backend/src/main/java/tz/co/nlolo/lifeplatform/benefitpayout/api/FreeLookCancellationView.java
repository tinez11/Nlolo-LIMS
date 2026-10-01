package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A free-look cancellation as a reader sees it.
 *
 * <p>Both figures are carried: what came in and what goes back. The difference is the deductions,
 * and showing the refund alone would hide the arithmetic the customer is entitled to check.
 */
public record FreeLookCancellationView(UUID cancellationId, String policyNumber, String status,
                                       BigDecimal premiumsCollected, BigDecimal refundAmount, String currency,
                                       String payeeRef, String requestedBy, String approvedBy,
                                       List<FreeLookDeductionInput> deductions) {}
