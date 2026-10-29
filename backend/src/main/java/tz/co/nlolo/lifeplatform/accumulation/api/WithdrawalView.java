package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WithdrawalView(UUID withdrawalId, String policyNumber, BigDecimal amount, String currency, String payeeRef,
                             String status, String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt,
                             /* How it is paid: MOBILE_MONEY, EFT or CHEQUE, and the bank account on an EFT. */
                             tz.co.nlolo.lifeplatform.PayoutRoute route,
                             /* Refused by finance (2026-10-29): who, when and why; null otherwise. */
                             String rejectedBy, Instant rejectedAt, String rejectionReason) {}
