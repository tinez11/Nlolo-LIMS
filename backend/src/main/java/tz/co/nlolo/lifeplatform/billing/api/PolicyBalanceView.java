package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A policy's balance (money movement step 5): what it holds now, and the due date of the last premium that money pays
 * in full if nothing else comes in -- the server's figure, never the browser's. {@code coversPremiumsTo} is null when
 * the balance pays no whole premium still to come.
 */
public record PolicyBalanceView(String policyNumber, BigDecimal balance, String currency, LocalDate coversPremiumsTo) {}
