package tz.co.nlolo.lifeplatform.billing.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.PayoutRoute;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.OwnWork;
import tz.co.nlolo.lifeplatform.billing.api.BalanceRefusedException;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceEntry;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceWithdrawal;
import tz.co.nlolo.lifeplatform.billing.domain.PolicyBalance;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BalanceEntryRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BalanceWithdrawalRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PolicyBalanceRepository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Withdrawals from a policy's balance (money movement step 3a, 2026-11-04): the payer's own money paid back by the
 * payout road. One person asks ({@code payouts.manage}); a second approves ({@code payouts.approve}), or the same
 * person holding {@code payouts.approve-own}, recorded against their name. The money leaves the balance at approval --
 * DR 2128 / CR 2430 refunds payable -- and the payment rail pays it (DR 2430 / CR the bank it left by). A transfer the
 * rail refuses puts it back on the balance.
 */
@Service
public class BalanceWithdrawals {

    /** The payout purpose payment records it under, so only billing takes the outcome (payment V23). */
    public static final String PURPOSE = "BALANCE_WITHDRAWAL";
    private static final Set<String> LIVE = Set.of("AWAITING_PAYEE", "REQUESTED", "APPROVED");

    private final PolicyBalanceRepository balances;
    private final BalanceEntryRepository entries;
    private final BalanceWithdrawalRepository withdrawals;
    private final ApplicationEventPublisher events;
    private final OwnWork ownWork;

    BalanceWithdrawals(PolicyBalanceRepository balances, BalanceEntryRepository entries,
                       BalanceWithdrawalRepository withdrawals, ApplicationEventPublisher events, OwnWork ownWork) {
        this.balances = balances;
        this.entries = entries;
        this.withdrawals = withdrawals;
        this.events = events;
        this.ownWork = ownWork;
    }

    /** Someone asks for money back off the balance, to this payee. At most what is on the balance now. */
    @Transactional
    public BalanceWithdrawal request(String policyNumber, BigDecimal amount, PayoutRoute route, String by) {
        UUID tenantId = TenantContext.get();
        PolicyBalance balance = balances.findForUpdate(tenantId, policyNumber)
            .orElseThrow(() -> new BalanceRefusedException("Policy " + policyNumber + " has no balance to withdraw from"));
        refuseIfInFlight(tenantId, policyNumber);
        BigDecimal now = entries.balanceOf(tenantId, policyNumber);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(now) > 0) {
            throw new BalanceRefusedException("Withdraw between 0.01 and " + now.setScale(2).toPlainString() + " "
                + balance.getCurrency() + ", what is on the policy's balance");
        }
        return withdrawals.save(BalanceWithdrawal.asked(tenantId, policyNumber, amount, balance.getCurrency(), route, by));
    }

    /**
     * The policy has ended with money on its balance: a withdrawal for all of it, waiting for finance to name the payee.
     * Nothing when the balance is empty or a withdrawal is already in flight.
     */
    @Transactional
    public void raiseOnPolicyEnd(UUID tenantId, String policyNumber) {
        PolicyBalance balance = balances.findForUpdate(tenantId, policyNumber).orElse(null);
        if (balance == null
                || withdrawals.existsByTenantIdAndPolicyNumberAndStatusIn(tenantId, policyNumber, LIVE)) {
            return;
        }
        BigDecimal left = entries.balanceOf(tenantId, policyNumber);
        if (left.signum() <= 0) return;
        withdrawals.save(BalanceWithdrawal.policyEnded(tenantId, policyNumber, left, balance.getCurrency()));
    }

    /** Finance names who receives a withdrawal the platform raised; it then waits for its second person. */
    @Transactional
    public BalanceWithdrawal namePayee(UUID withdrawalId, PayoutRoute route, String by) {
        BalanceWithdrawal w = find(withdrawalId);
        w.namePayee(route, by);
        return withdrawals.save(w);
    }

    /**
     * The second person approves: under the balance's lock the money leaves it (a WITHDRAWAL entry and
     * billing.BalanceWithdrawalApproved) and payment is asked to pay it. Refused if the balance no longer holds it --
     * a premium may have been paid from it since it was asked for.
     */
    @Transactional
    public BalanceWithdrawal approve(UUID withdrawalId, String by) {
        BalanceWithdrawal w = find(withdrawalId);
        if (ownWork.mayActAlone("payouts.approve-own")) w.allowActingAlone();
        balances.findForUpdate(w.getTenantId(), w.getPolicyNumber()).orElseThrow();
        BigDecimal now = entries.balanceOf(w.getTenantId(), w.getPolicyNumber());
        if (w.getAmount().compareTo(now) > 0) {
            throw new BalanceRefusedException("The policy's balance is " + now.setScale(2).toPlainString() + " "
                + w.getCurrency() + " now, less than this withdrawal; refuse it and ask for what is there");
        }
        w.approve(by);
        withdrawals.save(w);
        if (w.isActedAlone()) {
            ownWork.record("Withdrawal approved by the person who asked for it", w.getWithdrawalId().toString(),
                w.getPolicyNumber(), by);
        }
        entries.save(BalanceEntry.withdrawal(w.getTenantId(), w.getPolicyNumber(), w.getAmount(), w.getCurrency(),
            w.getWithdrawalId(), by));
        events.publishEvent(DomainEventEnvelope.of("billing.BalanceWithdrawalApproved", w.getTenantId(),
            Map.of("withdrawalId", w.getWithdrawalId(), "policyNumber", w.getPolicyNumber(),
                   "amount", money(w))));
        Map<String, Object> request = new HashMap<>(w.route().toPayload());
        request.put("withdrawalId", w.getWithdrawalId().toString());
        request.put("idempotencyKey", "balance-withdrawal:" + w.getWithdrawalId());
        request.put("policyNumber", w.getPolicyNumber());
        request.put("amount", money(w));
        events.publishEvent(DomainEventEnvelope.of("billing.BalanceWithdrawalPayoutRequested", w.getTenantId(), request));
        return w;
    }

    @Transactional
    public BalanceWithdrawal reject(UUID withdrawalId, String by, String why) {
        BalanceWithdrawal w = find(withdrawalId);
        if (ownWork.mayActAlone("payouts.approve-own")) w.allowActingAlone();
        w.reject(by, why);
        if (w.isActedAlone()) {
            ownWork.record("Withdrawal refused by the person who asked for it", w.getWithdrawalId().toString(),
                w.getPolicyNumber(), by);
        }
        return withdrawals.save(w);
    }

    /** The rail paid it: billing.BalanceWithdrawalPaid (DR 2430 / CR the bank it left by). Once only. */
    @Transactional
    public void markPaid(UUID withdrawalId, UUID disbursementId) {
        withdrawals.findById(withdrawalId).ifPresent(w -> {
            if (!w.markPaid(disbursementId)) return;
            withdrawals.save(w);
            events.publishEvent(DomainEventEnvelope.of("billing.BalanceWithdrawalPaid", w.getTenantId(),
                Map.of("withdrawalId", w.getWithdrawalId().toString(), "policyNumber", w.getPolicyNumber(),
                       "amount", money(w))));
        });
    }

    /**
     * The rail refused it: the money is back on the balance (a DEPOSIT from WITHDRAWAL_RETURNED, not fresh money) and
     * billing.BalanceWithdrawalReturned undoes the payable (DR 2430 / CR 2128). Once only.
     */
    @Transactional
    public void markFailed(UUID withdrawalId, UUID disbursementId) {
        withdrawals.findById(withdrawalId).ifPresent(w -> {
            if (!w.markFailed(disbursementId)) return;
            withdrawals.save(w);
            entries.save(BalanceEntry.returned(w.getTenantId(), w.getPolicyNumber(), w.getAmount(), w.getCurrency(),
                w.getWithdrawalId()));
            events.publishEvent(DomainEventEnvelope.of("billing.BalanceWithdrawalReturned", w.getTenantId(),
                Map.of("withdrawalId", w.getWithdrawalId().toString(), "policyNumber", w.getPolicyNumber(),
                       "amount", money(w))));
        });
    }

    @Transactional(readOnly = true)
    public List<BalanceWithdrawal> listFor(String policyNumber) {
        return withdrawals.findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(TenantContext.get(), policyNumber);
    }

    @Transactional(readOnly = true)
    public List<BalanceWithdrawal> waiting(String status) {
        return withdrawals.findByTenantIdAndStatusOrderByRequestedAtAsc(TenantContext.get(), status);
    }

    @Transactional(readOnly = true)
    public BalanceWithdrawal get(UUID withdrawalId) {
        return find(withdrawalId);
    }

    private void refuseIfInFlight(UUID tenantId, String policyNumber) {
        if (withdrawals.existsByTenantIdAndPolicyNumberAndStatusIn(tenantId, policyNumber, LIVE)) {
            throw new BalanceRefusedException("A withdrawal from this policy's balance is already waiting; finish or refuse it first");
        }
    }

    private BalanceWithdrawal find(UUID withdrawalId) {
        return withdrawals.findByWithdrawalIdAndTenantId(withdrawalId, TenantContext.get())
            .orElseThrow(() -> new BalanceRefusedException("No withdrawal " + withdrawalId + " on this tenant's books"));
    }

    private static Map<String, Object> money(BalanceWithdrawal w) {
        return Map.of("amount", w.getAmount().setScale(2).toPlainString(), "currencyCode", w.getCurrency());
    }
}
