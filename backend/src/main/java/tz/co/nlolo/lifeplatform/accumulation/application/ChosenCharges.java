package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyAccountChargeApi;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeApi;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeView;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Account charges a savings policy is charged by, turned into ledger lines: the ones it was issued on (2026-10-09,
 * product V32), or else the ones its product version picks (V34, the user, 2026-10-22). A policy with neither is charged by
 * its version's own rows, as before -- {@link Charges#catalogue()} false is that signal.
 *
 * <p>Every line is booked as a charge: a deposit-time charge as {@code ALLOCATION_CHARGE}, every other as
 * {@code POLICY_FEE}, the two types accounting already posts as fee income (G-06, Dr 2320 / Cr 7310). The charge's own
 * name is on the line, so a statement reads "Withdrawal fee (1.5%)", not the type.
 */
@Component
public class ChosenCharges {

    private static final Set<String> AT_DEPOSIT = Set.of("DEPOSIT", "PREMIUM", "TRANSFER_IN", "OPENING");

    private final PolicyAccountChargeApi policyCharges;
    private final AccountChargeApi catalogue;
    private final ProductApi productApi;

    public ChosenCharges(PolicyAccountChargeApi policyCharges, AccountChargeApi catalogue, ProductApi productApi) {
        this.policyCharges = policyCharges;
        this.catalogue = catalogue;
        this.productApi = productApi;
    }

    /**
     * What a policy is charged by.
     *
     * @param catalogue true when Account charges decide it -- the policy's own or its version's -- even if none of them
     *     falls in a given year; false when its version's own rows do
     * @param all every one of them, whatever the year
     */
    public record Charges(boolean catalogue, List<AccountChargeView> all) {

        /** The ones taken in {@code policyYear}. */
        public List<AccountChargeView> in(int policyYear) {
            return all.stream().filter(c -> c.covers(policyYear)).toList();
        }
    }

    /** The policy's own charges, or else its version's; neither is {@code catalogue} false. */
    public Charges of(String policyNumber, UUID productVersionId) {
        List<UUID> ids = policyCharges.accountCharges(policyNumber);
        return inOrder(ids.isEmpty() ? productApi.resolveAccumulationPlan(productVersionId).accountChargeIds() : ids);
    }

    /** A version's own Account charges, before any policy exists -- what a quotation is charged. */
    public List<AccountChargeView> ofVersion(AccumulationPlan plan) {
        return inOrder(plan.accountChargeIds()).all();
    }

    private Charges inOrder(List<UUID> ids) {
        if (ids.isEmpty()) {
            return new Charges(false, List.of());
        }
        // In the order they were chosen: when there is not room for them all, the first are taken first.
        List<UUID> order = ids;
        List<AccountChargeView> found = new ArrayList<>(catalogue.resolve(ids));
        found.sort(Comparator.comparingInt(c -> order.indexOf(c.chargeId())));
        return new Charges(true, found);
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
