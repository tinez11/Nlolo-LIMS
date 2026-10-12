package tz.co.nlolo.lifeplatform.distribution.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Read view of {@code distribution.domain.CommissionAccrual} -- the per-event line items a
 * {@link CommissionStatementView} totals. {@code reversesAccrualId} is non-null exactly for a
 * clawback row (M7 user decision 2).
 *
 * <p>Agent commission view: {@code clientPartyId}/{@code clientName} name the policy's holder and
 * {@code productId}/{@code productName} its product, all from distribution's own policy projection --
 * null when no projection row exists for the policy, or a name cannot be resolved. {@code accruedOn}
 * is the Dar es Salaam day the line was booked. */
public record CommissionAccrualView(UUID accrualId, UUID agentId, UUID statementId, String policyNumber,
                                     TierType tierType, BigDecimal amount, String currency, String period,
                                     String sourceRef, UUID reversesAccrualId,
                                     UUID clientPartyId, String clientName, UUID productId, String productName,
                                     LocalDate accruedOn) {}
