package tz.co.nlolo.lifeplatform.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the payments register (2026-10-27): money that came in or went out, whichever ledger it lives in.
 *
 * @param direction    IN (a collection) or OUT (a disbursement)
 * @param purpose      what the money is for: PREMIUM, ACCOUNT_TOP_UP, CLAIM_SETTLEMENT, WITHDRAWAL_PAYOUT…
 * @param method       how it moved: MOBILE_MONEY or EFT
 * @param status       the row's own status: PENDING, AWAITING_EXECUTION, IN_DOUBT, CONFIRMED, COMPLETED, FAILED
 * @param counterparty who paid (IN) or who is paid (OUT), as recorded
 * @param reference    the rail's or the bank's reference, once there is one
 * @param sourceRef    the owning record's id (an invoice, a claim, an instalment, a withdrawal)
 * @param policyNumber the policy it is for, where the request named one; null on older rows and on commission
 * @param executedBy   who recorded a bank transfer as made; null otherwise
 */
public record PaymentRecordView(UUID id, String direction, String purpose, String method, String status,
                                BigDecimal amount, String currency, String counterparty, String reference,
                                String sourceRef, String policyNumber, Instant createdAt, String executedBy,
                                Instant executedAt) {}
