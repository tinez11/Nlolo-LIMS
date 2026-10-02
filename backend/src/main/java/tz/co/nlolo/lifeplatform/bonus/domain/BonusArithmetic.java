package tz.co.nlolo.lifeplatform.bonus.domain;

import tz.co.nlolo.lifeplatform.product.api.BonusMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure. Every figure rounds ONCE, to 2 dp, HALF_EVEN -- the ledger's rounding. */
public final class BonusArithmetic {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE = new BigDecimal("12");

    private BonusArithmetic() {}

    /** Q1. COMPOUND's base includes everything attached BEFORE this declaration's valuation date. */
    public static BigDecimal reversionary(BonusMethod method, BigDecimal sumAssured, BigDecimal attachedBefore, BigDecimal ratePercent) {
        return base(method, sumAssured, attachedBefore).multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }

    /** Q4: the last declared rate, for the whole months since, on the same base. */
    public static BigDecimal interim(BonusMethod method, BigDecimal sumAssured, BigDecimal attached, BigDecimal ratePercent,
                                     long wholeMonths) {
        if (wholeMonths <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return base(method, sumAssured, attached).multiply(ratePercent).multiply(BigDecimal.valueOf(wholeMonths))
            .divide(HUNDRED.multiply(TWELVE), 2, RoundingMode.HALF_EVEN);
    }

    /** Q5: a percentage of attached reversionary bonuses. */
    public static BigDecimal terminal(BigDecimal attached, BigDecimal ratePercent) {
        return attached.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }

    public static long wholeMonths(LocalDate from, LocalDate to) {
        return Math.max(0, ChronoUnit.MONTHS.between(from, to));
    }

    /** The base a rate applies to: the sum assured, plus attached bonuses on a COMPOUND version. */
    public static BigDecimal base(BonusMethod method, BigDecimal sumAssured, BigDecimal attached) {
        return method == BonusMethod.COMPOUND ? sumAssured.add(attached) : sumAssured;
    }
}
