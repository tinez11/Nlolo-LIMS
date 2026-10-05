package tz.co.nlolo.lifeplatform.unitlinked.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A partial withdrawal (U2). Before it is priced the {@code estimated*} figures are indications at the latest approved
 * prices -- the sale itself is priced at the first price after approval; once priced, {@code proceeds},
 * {@code surrenderCharge} and {@code shortfall} are what happened. {@code newSumAssured} is set only when the version
 * cuts cover by the withdrawal.
 */
public record WithdrawalView(UUID withdrawalId, String policyNumber,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossAmount,
                             List<Named> funds, String payeeRef, String status,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal chargePercent,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal estimatedCharge,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal estimatedNet,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal estimatedRemaining,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal newSumAssured,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal proceeds,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal surrenderCharge,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shortfall,
                             String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt) {

    public record Named(String fundCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount) {}
}
