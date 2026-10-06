package tz.co.nlolo.lifeplatform.reinsurance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * One treaty's bordereau for one month (IFRS 17 I3c, posting guide K-01..K-03) -- pure arithmetic, no I/O.
 *
 * <p>The user's answers (2026-10-06) it encodes:
 * <ul>
 *   <li>Ceded premium on ORIGINAL TERMS: the cession's share of the policy's own premium, as a monthly amount
 *       (an instalment x instalments a year / 12). A single premium is charged once, in the month cover began.</li>
 *   <li>A FULL month for every month cover was in force at any point -- no day pro-rata. Premiums that stopped
 *       (paid up, premium term over) stop the charge from the following month; cover, and recovery, go on.</li>
 *   <li>The treaty's commission not contingent on claims (K-02) is its percent of each line's premium.</li>
 *   <li>An XOL treaty's flat annual premium is charged one twelfth a month.</li>
 *   <li>Recoveries recorded in the month are listed to be matched (K-03) and post nothing here.</li>
 * </ul>
 * A line whose premium currency is not the treaty's is left off: no FX table exists, the cession's own rule.
 */
public final class BordereauCalculator {

    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private BordereauCalculator() {}

    public record Treaty(String currency, BigDecimal commissionPercent, BigDecimal xolAnnualPremium) {}

    /** A period the policy was on risk; {@code endsOn} null while it still is. */
    public record Cover(LocalDate startsOn, LocalDate endsOn) {}

    public record CededPolicy(String policyNumber, BigDecimal premiumShare, BigDecimal premium, String premiumCurrency,
                              String premiumFrequency, LocalDate premiumsEndOn, List<Cover> cover) {}

    public record Recovery(java.util.UUID claimId, String policyNumber, BigDecimal amount, String currency) {}

    public enum LineType { PREMIUM, XOL_PREMIUM, RECOVERY }

    public record Line(LineType type, String policyNumber, java.util.UUID claimId, BigDecimal premiumShare,
                       BigDecimal policyPremium, BigDecimal premium, BigDecimal commission, BigDecimal recovery) {}

    public record Result(List<Line> lines, int policyCount, BigDecimal premium, BigDecimal commission,
                         BigDecimal recoveries) {}

    public static Result calculate(YearMonth month, Treaty treaty, List<CededPolicy> policies, List<Recovery> recoveries) {
        List<Line> lines = new ArrayList<>();
        BigDecimal rate = treaty.commissionPercent() == null ? BigDecimal.ZERO : treaty.commissionPercent();
        for (CededPolicy p : policies) {
            if (!treaty.currency().equals(p.premiumCurrency()) || p.premiumShare() == null || p.premium() == null) {
                continue;
            }
            BigDecimal ceded = monthlyCededPremium(month, p);
            if (ceded.signum() <= 0) {
                continue;
            }
            lines.add(new Line(LineType.PREMIUM, p.policyNumber(), null, p.premiumShare(), p.premium(), ceded,
                commission(ceded, rate), BigDecimal.ZERO));
        }
        int policyCount = lines.size();
        if (treaty.xolAnnualPremium() != null && treaty.xolAnnualPremium().signum() > 0) {
            BigDecimal monthly = treaty.xolAnnualPremium().divide(TWELVE, 2, RoundingMode.HALF_UP);
            lines.add(new Line(LineType.XOL_PREMIUM, null, null, null, null, monthly, commission(monthly, rate),
                BigDecimal.ZERO));
        }
        for (Recovery r : recoveries) {
            if (treaty.currency().equals(r.currency()) && r.amount().signum() > 0) {
                lines.add(new Line(LineType.RECOVERY, r.policyNumber(), r.claimId(), null, null, BigDecimal.ZERO,
                    BigDecimal.ZERO, r.amount()));
            }
        }
        BigDecimal premium = lines.stream().map(Line::premium).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = lines.stream().map(Line::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovered = lines.stream().map(Line::recovery).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(List.copyOf(lines), policyCount, premium, commission, recovered);
    }

    /** What one ceded policy costs in this month: zero when it was not on risk, or no longer paying premiums. */
    static BigDecimal monthlyCededPremium(YearMonth month, CededPolicy p) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        boolean onRisk = p.cover().stream().anyMatch(c -> !c.startsOn().isAfter(last)
            && (c.endsOn() == null || !c.endsOn().isBefore(first)));
        if (!onRisk) {
            return BigDecimal.ZERO;
        }
        if ("SINGLE".equals(p.premiumFrequency())) {
            LocalDate began = p.cover().stream().map(Cover::startsOn).min(LocalDate::compareTo).orElse(null);
            return began != null && YearMonth.from(began).equals(month)
                ? p.premium().multiply(p.premiumShare()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        }
        if (p.premiumsEndOn() != null && p.premiumsEndOn().isBefore(first)) {
            return BigDecimal.ZERO;
        }
        return p.premium().multiply(BigDecimal.valueOf(instalmentsPerYear(p.premiumFrequency())))
            .multiply(p.premiumShare())
            .divide(TWELVE, 2, RoundingMode.HALF_UP);
    }

    /** A frequency nothing recorded (a row no backfill reached) is read as monthly, the platform's usual premium. */
    static int instalmentsPerYear(String frequency) {
        if (frequency == null) {
            return 12;
        }
        return switch (frequency) {
            case "QUARTERLY" -> 4;
            case "ANNUALLY" -> 1;
            default -> 12;
        };
    }

    private static BigDecimal commission(BigDecimal premium, BigDecimal percent) {
        return premium.multiply(percent).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
