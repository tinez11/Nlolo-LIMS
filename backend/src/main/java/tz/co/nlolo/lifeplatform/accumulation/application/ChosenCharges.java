package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyAccountChargeApi;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeApi;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The account charges a savings policy was issued on (2026-10-09, product V32), turned into ledger lines. A policy with
 * none chosen is charged by its product version's own rows, as before -- {@link #chosen} returning empty is that signal.
 *
 * <p>Every line is booked as a charge: a deposit-time charge as {@code ALLOCATION_CHARGE}, every other as
 * {@code POLICY_FEE}, the two types accounting already posts as fee income (G-06, Dr 2320 / Cr 7310). The charge's own
 * name is on the line, so a statement reads "Withdrawal fee (1.5%)", not the type.
 */
@Component
public class ChosenCharges {

    private static final Set<String> AT_DEPOSIT = Set.of("DEPOSIT", "OPENING");

    private final PolicyAccountChargeApi policyCharges;
    private final AccountChargeApi catalogue;

    public ChosenCharges(PolicyAccountChargeApi policyCharges, AccountChargeApi catalogue) {
        this.policyCharges = policyCharges;
        this.catalogue = catalogue;
    }

    /** The policy's chosen charges; empty means its version's own. */
    public List<AccountChargeView> chosen(String policyNumber) {
        var ids = policyCharges.accountCharges(policyNumber);
        return ids.isEmpty() ? List.of() : catalogue.resolve(ids);
    }

    /** Whether the organisation lets a charge take an account below its product's minimum balance. */
    public boolean mayGoBelowMinimum() {
        return catalogue.mayGoBelowMinimum();
    }

    /**
     * The lines for every charge in {@code charges} taken {@code when}, each on {@code base}, none taking more than
     * {@code room} between them -- what the account holds above the floor it may not be charged through.
     *
     * @return the lines, and in total no more than {@code room}
     */
    public List<LedgerService.Line> lines(List<AccountChargeView> charges, Set<String> when, BigDecimal base,
                                          BigDecimal room, LocalDate on) {
        List<LedgerService.Line> out = new ArrayList<>();
        BigDecimal left = room.max(BigDecimal.ZERO);
        for (AccountChargeView charge : charges) {
            if (!when.contains(charge.when())) {
                continue;
            }
            BigDecimal taken = charge.on(base).min(left);
            if (taken.signum() <= 0) {
                continue;
            }
            left = left.subtract(taken);
            EntryType type = AT_DEPOSIT.contains(charge.when()) ? EntryType.ALLOCATION_CHARGE : EntryType.POLICY_FEE;
            out.add(LedgerService.Line.of(type, taken.negate(), on, charge.label()));
        }
        return out;
    }

    /** What {@code lines} take in all, as a positive amount. */
    public static BigDecimal total(List<LedgerService.Line> lines) {
        return lines.stream().map(LedgerService.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add).negate();
    }
}
