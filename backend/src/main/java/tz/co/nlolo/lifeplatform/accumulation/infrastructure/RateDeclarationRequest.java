package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RateDeclarationRequest(
    @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent,
    @NotNull LocalDate effectiveFrom) {}
