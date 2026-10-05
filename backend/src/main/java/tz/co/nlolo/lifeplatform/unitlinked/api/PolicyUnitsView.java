package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A unit-linked policy's units (spec §10): what it holds and what each holding is worth at the fund's latest
 * approved price (shown, never posted), what is waiting for a forward price and the date it is bound to, and every
 * ledger entry.
 */
public record PolicyUnitsView(String policyNumber, List<Holding> holdings, BigDecimal totalValue, String currency,
                              List<Pending> pending, List<Entry> entries, boolean frozen, String frozenReason) {

    public record Holding(String fundCode, String fundName, BigDecimal units, BigDecimal price, LocalDate priceDate,
                          BigDecimal value) {}

    public record Pending(UUID orderId, String fundCode, String side, BigDecimal amount, boolean sellAll, String purpose,
                          Instant receivedAt, LocalDate boundDate) {}

    public record Entry(UUID entryId, String fundCode, String type, BigDecimal units, BigDecimal price, BigDecimal amount,
                        LocalDate valuationDate, LocalDate boundDate, String sourceRef, Instant createdAt) {}
}
