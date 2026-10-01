package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RateDeclarationView(UUID declarationId, UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom,
                                  RateDeclarationStatus status, String proposedBy, Instant proposedAt,
                                  String approvedBy, Instant approvedAt) {}
