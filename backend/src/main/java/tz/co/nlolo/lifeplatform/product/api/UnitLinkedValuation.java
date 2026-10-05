package tz.co.nlolo.lifeplatform.product.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The units side of the unit-linked reconciliation (product step 6, plan R10): each fund's units in issue at its
 * latest approved price. Declared here because finaccounting, which owns the 2150 balance it is compared with, may
 * depend only on product and refdata; implemented by unitlinked, which owns the units.
 */
public interface UnitLinkedValuation {

    List<FundValuation> valuations();

    record FundValuation(String fundCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitsInIssue, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price, LocalDate priceDate, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value,
                         String currency) {}
}
