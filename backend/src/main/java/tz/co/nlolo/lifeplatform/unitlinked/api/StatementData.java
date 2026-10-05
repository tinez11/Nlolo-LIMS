package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What a unit statement says (U2, spec §6), the one source the PDF is laid out from: each fund's units the day before
 * the period and on its last day, priced by the latest approved price on or before that day -- shown with that price's
 * own date, and with NO price and no value where the fund had none yet (never zero, never an older price passed off);
 * every ledger line in the period; and its totals.
 */
public record StatementData(String policyNumber, LocalDate from, LocalDate to, List<Position> opening,
                            List<Position> closing, List<Line> lines, BigDecimal paidIn,
                            Map<String, BigDecimal> chargesByType, BigDecimal paidOut, String currency) {

    /** {@code price}, {@code priceDate} and {@code value} are null when the fund had no approved price yet. */
    public record Position(String fundCode, BigDecimal units, BigDecimal price, LocalDate priceDate, BigDecimal value) {}

    public record Line(LocalDate date, String type, String fundCode, BigDecimal units, BigDecimal price, BigDecimal amount) {}

    /** The total of the priced positions; a position with no price yet is not in it. */
    public static BigDecimal valueOf(List<Position> positions) {
        return positions.stream().map(Position::value).filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The newest price date among the positions, or null when none is priced. */
    public static LocalDate priceDateOf(List<Position> positions) {
        return positions.stream().map(Position::priceDate).filter(java.util.Objects::nonNull)
            .max(LocalDate::compareTo).orElse(null);
    }
}
