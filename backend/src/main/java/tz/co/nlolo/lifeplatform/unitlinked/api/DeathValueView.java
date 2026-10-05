package tz.co.nlolo.lifeplatform.unitlinked.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/**
 * What a unit-linked death claim pays (spec §7): under HIGHER_OF the larger of the sum assured and the units' sale
 * proceeds, under SUM_ASSURED_PLUS_FUND both, plus every cost of insurance charged after the date of death,
 * refunded. {@code proceeds} includes any premium returned because it was still waiting for units.
 */
public record DeathValueView(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal benefit, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal proceeds, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal costOfInsuranceRefund,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sumAssured, String deathRule, String currency) {}
