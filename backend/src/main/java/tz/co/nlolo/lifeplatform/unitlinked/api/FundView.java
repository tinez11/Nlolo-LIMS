package tz.co.nlolo.lifeplatform.unitlinked.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

public record FundView(UUID fundId, String code, String name, String currency, String assetClass,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal annualManagementChargePercent, LocalTime cutOffTime, String status,
                       String createdBy, Instant createdAt, String closedBy, Instant closedAt) {}
