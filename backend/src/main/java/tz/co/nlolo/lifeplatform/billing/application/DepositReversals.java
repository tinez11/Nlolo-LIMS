package tz.co.nlolo.lifeplatform.billing.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.OwnWork;
import tz.co.nlolo.lifeplatform.billing.api.BalanceRefusedException;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceEntry;
import tz.co.nlolo.lifeplatform.billing.domain.DepositReversal;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BalanceEntryRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.DepositReversalRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PolicyBalanceRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reversing a deposit whose money never really arrived (money movement step 3b, 2026-11-04): a bounced cheque, a
 * reversed mobile-money payment. One person asks ({@code collections.manage}); a second confirms
 * ({@code collections.approve}), or the same person holding {@code collections.approve-own}, recorded against their
 * name. On confirmation:
 * <ol>
 *   <li>the deposit comes off the balance -- a REVERSAL entry and billing.DepositReversed (DR 2128 / CR the account it
 *       had reached);</li>
 *   <li>if the balance is then below zero, the premiums the money paid reopen, newest first -- a PREMIUM_REOPENED entry
 *       each and billing.PremiumPaidReversed (DR the receivable / CR 2128, the levy reversed), and the invoice is owed
 *       again;</li>
 *   <li>an invoice that was PAID and is no longer announces billing.PremiumCollectionReversed, and distribution claws
 *       back the commission booked on it (user decision A, 2026-11-04).</li>
 * </ol>
 * Money already withdrawn cannot be reopened: the balance then stays below zero and reads "Owes".
 */
@Service
public class DepositReversals {

    private static final Set<String> LIVE = Set.of("REQUESTED", "CONFIRMED");

    private final BalanceEntryRepository entries;
    private final PolicyBalanceRepository balances;
    private final DepositReversalRepository reversals;
    private final PremiumInvoiceRepository invoices;
    private final ApplicationEventPublisher events;
    private final OwnWork ownWork;

    DepositReversals(BalanceEntryRepository entries, PolicyBalanceRepository balances, DepositReversalRepository reversals,
                     PremiumInvoiceRepository invoices, ApplicationEventPublisher events, OwnWork ownWork) {
        this.entries = entries;
        this.balances = balances;
        this.reversals = reversals;
        this.invoices = invoices;
        this.events = events;
        this.ownWork = ownWork;
    }

    /** Ask to reverse the deposit a confirmed payment made (the receipt's id is the payment's). */
    @Transactional
    public DepositReversal request(UUID paymentId, String reason, String by) {
        UUID tenantId = TenantContext.get();
        BalanceEntry deposit = entries.findByTenantIdAndEntryTypeAndPaymentId(tenantId, BalanceEntry.DEPOSIT, paymentId)
            .orElseThrow(() -> new BalanceRefusedException("That payment put nothing on a policy's balance, so there is "
                + "no deposit to reverse"));
        if (reversals.existsByTenantIdAndPaymentIdAndStatusIn(tenantId, paymentId, LIVE)) {
            throw new BalanceRefusedException("This deposit is already reversed, or a reversal is waiting to be confirmed");
        }
        return reversals.save(new DepositReversal(deposit, reason, by));
    }

    /** The second person confirms it: the money comes off, and what it paid reopens newest first. */
    @Transactional
    public DepositReversal confirm(UUID reversalId, String by) {
        DepositReversal r = find(reversalId);
        if (ownWork.mayActAlone("collections.approve-own")) r.allowActingAlone();
        r.confirm(by);
        reversals.save(r);
        if (r.isActedAlone()) {
            ownWork.record("Deposit reversal confirmed by the person who asked for it", r.getReversalId().toString(),
                r.getPolicyNumber(), by);
        }
        UUID tenantId = r.getTenantId();
        String policyNumber = r.getPolicyNumber();
        balances.findForUpdate(tenantId, policyNumber).orElseThrow();
        BalanceEntry deposit = entries.findByTenantIdAndEntryTypeAndPaymentId(tenantId, BalanceEntry.DEPOSIT,
            r.getPaymentId()).orElseThrow();
        BalanceEntry reversal = entries.save(BalanceEntry.reversal(deposit, by));
        events.publishEvent(DomainEventEnvelope.of("billing.DepositReversed", tenantId,
            Map.of("entryId", reversal.getEntryId(), "paymentId", r.getPaymentId(), "policyNumber", policyNumber,
                   "amount", money(deposit.getAmount(), deposit.getCurrency()),
                   "receiptMethod", deposit.getMethod() == null ? "MOBILE_MONEY" : deposit.getMethod())));
        reopenWhatItPaid(tenantId, policyNumber, by);
        return r;
    }

    @Transactional
    public DepositReversal reject(UUID reversalId, String by, String why) {
        DepositReversal r = find(reversalId);
        if (ownWork.mayActAlone("collections.approve-own")) r.allowActingAlone();
        r.reject(by, why);
        return reversals.save(r);
    }

    /** While the balance is below nothing, undo the premiums paid from it, newest first, as far as each was paid. */
    private void reopenWhatItPaid(UUID tenantId, String policyNumber, String by) {
        BigDecimal short_ = entries.balanceOf(tenantId, policyNumber).negate();
        if (short_.signum() <= 0) return;
        List<BalanceEntry> paidNewestFirst = entries.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)
            .stream().filter(e -> BalanceEntry.PREMIUM_PAID.equals(e.getEntryType()))
            .sorted(Comparator.comparing(BalanceEntry::getCreatedAt).reversed()).toList();
        for (BalanceEntry paid : paidNewestFirst) {
            if (short_.signum() <= 0) break;
            BigDecimal alreadyReopened = entries.findByTenantIdAndReversesEntryId(tenantId, paid.getEntryId()).stream()
                .map(BalanceEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reopenable = paid.getAmount().subtract(alreadyReopened);
            if (reopenable.signum() <= 0) continue;
            BigDecimal take = short_.min(reopenable);
            PremiumInvoice invoice = invoices.findByInvoiceIdAndTenantId(paid.getInvoiceId(), tenantId).orElseThrow();
            boolean wasPaid = invoice.reopen(take);
            invoices.save(invoice);
            BalanceEntry reopened = entries.save(BalanceEntry.premiumReopened(paid, take, by));
            events.publishEvent(DomainEventEnvelope.of("billing.PremiumPaidReversed", tenantId,
                Map.of("entryId", reopened.getEntryId(), "invoiceId", invoice.getInvoiceId(), "policyNumber", policyNumber,
                       "amount", money(take, paid.getCurrency()))));
            if (wasPaid) {
                // Collection number of the payment undone: reopen() has already counted this reopening.
                events.publishEvent(DomainEventEnvelope.of("billing.PremiumCollectionReversed", tenantId,
                    Map.of("invoiceId", invoice.getInvoiceId(), "policyNumber", policyNumber,
                           "collection", invoice.collectionNumber() - 1,
                           "amount", money(invoice.getAmount(), invoice.getCurrency()))));
            }
            short_ = short_.subtract(take);
        }
    }

    @Transactional(readOnly = true)
    public List<DepositReversal> waiting() {
        return reversals.findByTenantIdAndStatusOrderByRequestedAtAsc(TenantContext.get(), "REQUESTED");
    }

    @Transactional(readOnly = true)
    public DepositReversal get(UUID reversalId) {
        return find(reversalId);
    }

    private DepositReversal find(UUID reversalId) {
        return reversals.findByReversalIdAndTenantId(reversalId, TenantContext.get())
            .orElseThrow(() -> new BalanceRefusedException("No reversal " + reversalId + " on this tenant's books"));
    }

    private static Map<String, Object> money(BigDecimal amount, String currency) {
        return Map.of("amount", amount.setScale(2).toPlainString(), "currencyCode", currency);
    }
}
