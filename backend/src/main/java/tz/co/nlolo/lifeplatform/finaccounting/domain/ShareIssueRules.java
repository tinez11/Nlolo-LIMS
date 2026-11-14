package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An issue of shares for cash (2026-11-14, shareholders register): what of the money is share capital (shares ×
 * nominal value) and what is share premium (the rest), and what makes an issue impossible. Pure.
 */
public final class ShareIssueRules {

    public static final String PREMIUM_ACCOUNT = "3120";

    private ShareIssueRules() {}

    public record Split(BigDecimal capital, BigDecimal premium) {}

    public static Split split(long shares, BigDecimal nominal, BigDecimal price, BigDecimal amountPaid) {
        BigDecimal capital = nominal.multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
        return new Split(capital, amountPaid.setScale(2, RoundingMode.HALF_UP).subtract(capital));
    }

    /** Shares are never issued below nominal (a discount), are paid in full, and the money has already come. */
    public static List<String> problems(long shares, BigDecimal nominal, BigDecimal price, BigDecimal amountPaid,
                                        LocalDate receivedOn, LocalDate today) {
        List<String> problems = new ArrayList<>();
        if (price.compareTo(nominal) < 0) {
            problems.add("Shares cannot be issued below their nominal value of " + money(nominal));
        } else {
            BigDecimal due = price.multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
            if (amountPaid.setScale(2, RoundingMode.HALF_UP).compareTo(due) != 0) {
                problems.add("The amount paid must be shares × price: " + money(due));
            }
        }
        if (receivedOn.isAfter(today)) {
            problems.add("The money cannot be received in the future");
        }
        return problems;
    }

    /** Ordinary shares to 3110, preference shares to 3130. */
    public static String capitalAccount(String classCode) {
        return "PREFERENCE".equals(classCode) ? "3130" : "3110";
    }

    static String money(BigDecimal v) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT))
            .format(v.setScale(2, RoundingMode.HALF_UP));
    }
}
