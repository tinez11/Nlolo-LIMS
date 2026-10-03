package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A bonus declaration on a product as at its valuation date. */
public record BonusDeclarationView(UUID declarationId, UUID productId, LocalDate valuationDate,
                                   BigDecimal reversionaryRatePercent, BigDecimal terminalRatePercent,
                                   DeclarationStatus status, String proposedBy, Instant proposedAt,
                                   String approvedBy, Instant approvedAt, Instant completedAt) {}
