package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProductSnapshotView(UUID productId, UUID productVersionId, LocalDate effectiveDate,
                                   IfrsMeasurementModel ifrsMeasurementModel, int gracePeriodDays, BigDecimal maxLoanToValuePercent) {}
