package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.time.LocalTime;

/** A new fund in the register. The cut-off is Dar es Salaam civil time. */
public record CreateFund(String code, String name, String currency, String assetClass,
                         BigDecimal annualManagementChargePercent, LocalTime cutOffTime) {}
