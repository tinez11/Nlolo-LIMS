package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much more a version charges for paying in instalments rather than once a year.
 *
 * <p>A percentage added to the ANNUAL premium before it is divided, not a modal factor. Both
 * express the same thing; only this one can be shown to a policyholder as a reason. {@code 0.0875}
 * does not announce that it is a five percent uplift, and {@link ProductApi.PremiumQuoteView}
 * exists precisely so an illustration can be explained.
 *
 * <p>Why a product charges more for monthly at all: the annual payer's whole premium is available
 * to invest on day one, twelve collections cost more than one, monthly business lapses more often
 * and lapses part-paid, and commission has already been accrued against premium that will not
 * arrive. Market convention is monthly five to nine percent above annual, quarterly two to four.
 *
 * <p>Deliberately NOT a {@code rating_table} factor type, which would have reused the authoring
 * form and the publish-time rules. That table is RISK rating — it feeds {@code RiskProfile} and so
 * an underwriting decision — and a payment frequency is not a risk fact. One table meaning two
 * things is the ambiguity that produced this platform's band-string defects.
 *
 * <p>A record rather than two more parameters because {@code publishVersion} already takes ten;
 * {@code IndividualRegistration} records the same reasoning for the same reason.
 */
public record FrequencyLoading(BigDecimal monthlyPercent, BigDecimal quarterlyPercent) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public FrequencyLoading {
        monthlyPercent = normalise("Monthly", monthlyPercent);
        quarterlyPercent = normalise("Quarterly", quarterlyPercent);
    }

    private static BigDecimal normalise(String label, BigDecimal percent) {
        if (percent == null) {
            return BigDecimal.ZERO;
        }
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(label + " frequency loading must be between 0 and 100"
                + " percent, was " + percent.toPlainString());
        }
        return percent;
    }

    /** No loading: every frequency costs the same over a year. A real pricing decision. */
    public static FrequencyLoading none() {
        return new FrequencyLoading(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Always zero for {@code ANNUALLY} — it is the baseline the others load against. */
    public BigDecimal percentFor(PremiumFrequency frequency) {
        return switch (frequency) {
            case MONTHLY -> monthlyPercent;
            case QUARTERLY -> quarterlyPercent;
            case ANNUALLY -> BigDecimal.ZERO;
        };
    }

    /**
     * The annual premium this frequency is actually divided from.
     *
     * <p>The ONE place this arithmetic lives. Both the quote path and the issuance path call it,
     * which is the point of this change: an illustration and the first invoice must come from one
     * formula. Full precision is kept — the caller rounds once, at the end.
     */
    public BigDecimal applyTo(BigDecimal annualPremium, PremiumFrequency frequency) {
        BigDecimal percent = percentFor(frequency);
        if (percent.signum() == 0) {
            return annualPremium;
        }
        return annualPremium.multiply(BigDecimal.ONE.add(percent.divide(HUNDRED, 6, RoundingMode.HALF_UP)));
    }
}
