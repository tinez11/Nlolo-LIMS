package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a version's authored payout rows into one policy's dated amounts -- the guide's
 * "calculated and stored on the day the policy is issued" (§16).
 *
 * <p>Policy year N runs from {@code start + (N-1) years} to {@code start + N years}, and its k-th
 * instalment falls {@code monthsApart x k} months into that year -- so the last one lands exactly
 * on the anniversary, and a yearly row pays on the anniversary rather than at the start of the
 * year it is named for. Nothing is dated after the policy's maturity date, because a product's
 * row range is authored for every policy sold on the version and this one may be shorter.
 */
public final class ScheduleExpander {

    /**
     * One amount the policy will owe. {@code amount} is null only for a premium return, whose
     * value depends on premiums actually collected and so cannot be known at issue.
     */
    public record Planned(PayoutKind kind, int rowOrder, LocalDate dueDate, BigDecimal amount) {}

    private ScheduleExpander() {}

    public static List<Planned> expand(PayoutPlan plan, LocalDate start, LocalDate maturityDate, BigDecimal sumAssured) {
        List<Planned> out = new ArrayList<>();
        for (int order = 0; order < plan.rows().size(); order++) {
            PayoutRowInput row = plan.rows().get(order);
            switch (row.kind()) {
                case MATURITY -> out.add(new Planned(row.kind(), order, maturityDate, amountFor(row, sumAssured)));
                case RETURN_OF_PREMIUM -> out.add(new Planned(row.kind(), order, maturityDate, null));
                case SURVIVAL, INCOME -> expandRecurring(out, row, order, start, maturityDate, sumAssured);
            }
        }
        return out;
    }

    private static void expandRecurring(List<Planned> out, PayoutRowInput row, int order, LocalDate start,
                                        LocalDate maturityDate, BigDecimal sumAssured) {
        int parts = row.frequency().perYear();
        List<BigDecimal> split = PayoutArithmetic.splitYear(amountFor(row, sumAssured), parts);
        for (int year = row.fromPolicyYear(); year <= row.toPolicyYear(); year++) {
            LocalDate yearStart = start.plusYears(year - 1L);
            for (int k = 1; k <= parts; k++) {
                LocalDate due = yearStart.plusMonths((long) k * row.frequency().monthsApart());
                if (maturityDate != null && due.isAfter(maturityDate)) {
                    continue;
                }
                out.add(new Planned(row.kind(), order, due, split.get(k - 1)));
            }
        }
    }

    /** A fixed amount is money as authored; everything else is a percentage of the sum assured. */
    private static BigDecimal amountFor(PayoutRowInput row, BigDecimal sumAssured) {
        return row.amountBasis() == PayoutAmountBasis.FIXED
            ? row.amountValue().setScale(2, RoundingMode.HALF_EVEN)
            : PayoutArithmetic.percentOf(sumAssured, row.amountValue());
    }
}
