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

    /**
     * @param basis what the treaty is charged on (finding 166): the premium, the month's cost of insurance, or nothing
     * @param monthCostOfInsurance the cost of insurance taken from the policy's units in the month; read only on that
     *                             basis, null when none was taken
     */
    public record CededPolicy(String policyNumber, BigDecimal premiumShare, BigDecimal premium, String premiumCurrency,
                              String premiumFrequency, LocalDate premiumsEndOn, List<Cover> cover,
                              CessionBasis basis, BigDecimal monthCostOfInsurance) {

        /** A protection policy, charged on its premium. */
        public CededPolicy(String policyNumber, BigDecimal premiumShare, BigDecimal premium, String premiumCurrency,
                           String premiumFrequency, LocalDate premiumsEndOn, List<Cover> cover) {
            this(policyNumber, premiumShare, premium, premiumCurrency, premiumFrequency, premiumsEndOn, cover,
                CessionBasis.PREMIUM, null);
        }
    }

    public record Recovery(java.util.UUID claimId, String policyNumber, BigDecimal amount, String currency) {}

    /** What a policy cancelled in free-look was charged on bordereaux already posted (2026-11-08): taken back. */
    public record Reversal(String policyNumber, BigDecimal premium, BigDecimal commission) {}

    public enum LineType { PREMIUM, XOL_PREMIUM, RECOVERY, FREE_LOOK_REVERSAL }

    public record Line(LineType type, String policyNumber, java.util.UUID claimId, BigDecimal premiumShare,
                       BigDecimal policyPremium, BigDecimal premium, BigDecimal commission, BigDecimal recovery) {}

    public record Result(List<Line> lines, int policyCount, BigDecimal premium, BigDecimal commission,
                         BigDecimal recoveries,
                         /** Taken back from earlier bordereaux for policies cancelled in free-look; the premium and
                          *  commission above are this month's charge alone. */
                         BigDecimal premiumReversed, BigDecimal commissionReversed) {}

    public static Result calculate(YearMonth month, Treaty treaty, List<CededPolicy> policies, List<Recovery> recoveries) {
        return calculate(month, treaty, policies, recoveries, List.of());
    }

    public static Result calculate(YearMonth month, Treaty treaty, List<CededPolicy> policies, List<Recovery> recoveries,
                                   List<Reversal> reversals) {
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
            // The line's own premium is what the share was taken of: the month's cost of insurance for unit-linked.
            BigDecimal base = p.basis() == CessionBasis.COST_OF_INSURANCE ? p.monthCostOfInsurance() : p.premium();
            lines.add(new Line(LineType.PREMIUM, p.policyNumber(), null, p.premiumShare(), base, ceded,
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
        // This month's charge, before anything is taken back.
        BigDecimal premium = lines.stream().map(Line::premium).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = lines.stream().map(Line::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovered = lines.stream().map(Line::recovery).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 2026-11-08: a policy cancelled in free-look after it was charged was never on risk -- its charge comes back,
        // as its own lines whose amounts are what is taken back (every amount here is positive).
        BigDecimal premiumReversed = BigDecimal.ZERO;
        BigDecimal commissionReversed = BigDecimal.ZERO;
        for (Reversal r : reversals) {
            if (r.premium().signum() <= 0 && r.commission().signum() <= 0) {
                continue;
            }
            lines.add(new Line(LineType.FREE_LOOK_REVERSAL, r.policyNumber(), null, null, null, r.premium(),
                r.commission(), BigDecimal.ZERO));
            premiumReversed = premiumReversed.add(r.premium());
            commissionReversed = commissionReversed.add(r.commission());
        }
        return new Result(List.copyOf(lines), policyCount, premium, commission, recovered, premiumReversed,
            commissionReversed);
    }

    /** What one ceded policy costs in this month: zero when it was not on risk, or no longer paying premiums. */
    static BigDecimal monthlyCededPremium(YearMonth month, CededPolicy p) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        boolean onRisk = p.cover().stream().anyMatch(c -> !c.startsOn().isAfter(last)
            && (c.endsOn() == null || !c.endsOn().isBefore(first)));
        if (!onRisk || p.basis() == CessionBasis.NOT_CEDED) {
            return BigDecimal.ZERO;
        }
        // Finding 166: a unit-linked policy pays the treaty its share of the risk premium actually taken this month --
        // never of the premium that buys units, and nothing in a month no cost of insurance was taken.
        if (p.basis() == CessionBasis.COST_OF_INSURANCE) {
            return p.monthCostOfInsurance() == null ? BigDecimal.ZERO
                : p.monthCostOfInsurance().multiply(p.premiumShare()).setScale(2, RoundingMode.HALF_UP);
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
