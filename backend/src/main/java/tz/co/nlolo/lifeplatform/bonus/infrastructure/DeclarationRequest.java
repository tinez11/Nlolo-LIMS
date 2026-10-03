package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A bonus to declare on a product, as at a valuation date. */
public record DeclarationRequest(@NotNull LocalDate valuationDate, @NotNull BigDecimal reversionaryRatePercent,
                                 @NotNull BigDecimal terminalRatePercent) {}
