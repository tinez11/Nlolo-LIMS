package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A matured deposit whose money is still on the account: no number to pay it to (plan §R5). */
public record AwaitingPayeeView(String policyNumber, BigDecimal balance, String currency, LocalDate maturedOn) {}
