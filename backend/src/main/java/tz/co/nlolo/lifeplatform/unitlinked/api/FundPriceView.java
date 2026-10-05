package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FundPriceView(UUID priceId, UUID fundId, String fundCode, LocalDate valuationDate, BigDecimal price,
                            String status, String moveReason, UUID supersedesPriceId, String proposedBy,
                            Instant proposedAt, String approvedBy, Instant approvedAt) {}
