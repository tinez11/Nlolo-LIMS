package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalView(UUID withdrawalId, String policyNumber, BigDecimal amount, String currency, String payeeRef,
                             String status, String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt) {}
