package tz.co.nlolo.lifeplatform.benefitpayout.api;

import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One dated amount owed, as a reader sees it.
 *
 * <p>Both amounts are carried on purpose: {@code originalAmount} is what the contract authored and
 * {@code currentAmount} what is payable now, and a paid-up conversion is exactly the case where
 * they differ. Showing only the current figure would make a reduced benefit look like the agreed
 * one.
 */
public record PayoutInstalmentView(UUID instalmentId, String policyNumber, PayoutKind kind, LocalDate dueDate,
                                   BigDecimal originalAmount, BigDecimal currentAmount, String currency,
                                   String restatementReason, InstalmentStatus status, String statusReason,
                                   UUID streamId, String payeeRef, ProofOfLifeMethod proofOfLifeMethod,
                                   String reviewedBy, String approvedBy, UUID paymentRunId, int attempts,
                                   // Tax withheld at approval (product step 5); null before approval or with none checked.
                                   BigDecimal grossAmount, BigDecimal withheldAmount, BigDecimal netAmount) {}
