package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.api.ReturnLineView;
import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;

import java.math.RoundingMode;
import java.util.UUID;

/**
 * One line of a generated return, on the wire.
 *
 * <p>{@code value} is {@code Object} on purpose: it serializes as a {@link MoneyDto} when
 * {@link MetricName#isMonetary()} is true for this line's metric, and as a plain integer-valued
 * decimal {@link String} otherwise -- never a raw {@link java.math.BigDecimal} (which Jackson
 * would otherwise emit as a JSON number, exactly the float-in-the-wire-format class of defect
 * M6 fixed for {@code DisabilityClaimDetails.impairmentPercent}, which {@code openApi().isValid()}
 * provably does not catch on its own).
 *
 * <p>{@code numericValue} is stored as {@code NUMERIC(19,2)} (regreporting/V2 section 8) even for
 * count metrics, so a raw count reloaded from the database carries a {@code .00} scale --
 * {@code setScale(0, RoundingMode.UNNECESSARY)} strips that back to a genuine integer string and
 * throws (rather than silently truncating) if a count metric ever produced a fractional value,
 * which would itself be a bug.
 */
public record ReturnLineResponseDto(UUID returnLineId, int lineNo, String lineCode, String label,
                                     String metricName, Object value) {

    public static ReturnLineResponseDto from(ReturnLineView view) {
        boolean monetary = MetricName.valueOf(view.metricName()).isMonetary();
        Object value = monetary
            ? new MoneyDto(view.numericValue().toPlainString(), view.currency())
            : view.numericValue().setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        return new ReturnLineResponseDto(view.returnLineId(), view.lineNo(), view.lineCode(), view.label(),
            view.metricName(), value);
    }
}
