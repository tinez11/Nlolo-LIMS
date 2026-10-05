package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public record FundView(UUID fundId, String code, String name, String currency, String assetClass,
                       BigDecimal annualManagementChargePercent, LocalTime cutOffTime, String status,
                       String createdBy, Instant createdAt, String closedBy, Instant closedAt) {}
