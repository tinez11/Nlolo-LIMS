package tz.co.nlolo.lifeplatform.bonus.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a with-profits policy's bonuses are worth at an exit date (Q4, Q5): attached as at the date,
 * interim since the last valuation (only if eligible on the date), and terminal on what is attached.
 * The rates and declaration are the ones it was valued with; null when no declaration applied.
 */
public record BonusValuation(BigDecimal attached, BigDecimal interim, BigDecimal terminal,
                             BigDecimal interimRatePercent, BigDecimal terminalRatePercent, UUID declarationId) {
    public static BonusValuation none() {
        return new BonusValuation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null);
    }

    public BigDecimal total() { return attached.add(interim).add(terminal); }
}
