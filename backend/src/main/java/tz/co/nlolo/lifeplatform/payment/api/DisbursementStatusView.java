package tz.co.nlolo.lifeplatform.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One outbound payment instruction, as the rest of the platform reads it.
 *
 * <p><b>{@code payeeRef} and {@code createdAt} exist for the EFT work queue</b>, and their
 * absence was the reason that queue could not be built. A mobile-money payout needs no screen:
 * the aggregator calls back and the row completes itself, so nobody ever had to look at one. An
 * EFT has no callback — a finance officer moves the money in their bank and comes back to record
 * it — and a queue that shows an id, a status and an amount tells them the one thing they already
 * know and none of the things they need: <em>who</em> is being paid, and <em>how long</em> this
 * has been sitting there. A multi-million-shilling lender payout is not something to go looking
 * for the payee of in another system.
 */
public record DisbursementStatusView(UUID disbursementId, String idempotencyKey, DisbursementStatus status,
                                      BigDecimal amount, String currency, String purpose,
                                      String gatewayReference, String sourceRef, UUID batchId,
                                      String payeeRef, Instant createdAt) {}
