package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.BooksMatchView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.CashAccounts;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingQueueApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Do the books match? (money movement step 7, design §12.) Run after seeding and after every money test; it compares
 * the ledger with the records it summarises and says by how much each differs:
 *
 * <ul>
 *   <li>2128 Premiums received in advance against the sum of every policy balance;</li>
 *   <li>2122 + 2142 Premiums receivable against what invoices still owe, policy by policy;</li>
 *   <li>each cash account (1120, 1130, 1140) against the money the rail confirmed in less what it paid out, mapped by
 *       method with {@link CashAccounts} -- the platform's bank reconciliation, whatever the money was for;</li>
 *   <li>the trial balance: debits equal credits;</li>
 *   <li>no money event left unposted (the posting queue's open items).</li>
 * </ul>
 *
 * Here and not in finaccounting because it reads billing too, and billing already reaches finaccounting through policy
 * and distribution: a check owned by either would close a cycle. The dashboard module reads both and nothing reads it.
 *
 * <p>Cash is reconciled against all money moved, not only deposits and withdrawals as design §12 first worded it:
 * claims, benefits, loans and savings move the same accounts, so the narrower sum could never match (2026-11-05).
 */
@Service
public class BooksMatch {

    static final String PREMIUMS_IN_ADVANCE = "2128";

    private final FinaccountingApi finaccounting;
    private final PostingQueueApi postingQueue;
    private final BillingApi billing;
    private final PaymentApi payments;

    public BooksMatch(FinaccountingApi finaccounting, PostingQueueApi postingQueue, BillingApi billing, PaymentApi payments) {
        this.finaccounting = finaccounting;
        this.postingQueue = postingQueue;
        this.billing = billing;
        this.payments = payments;
    }

    public BooksMatchView check() {
        TrialBalanceView trial = finaccounting.trialBalance(null);
        List<BooksMatchView.Check> checks = new ArrayList<>();
        checks.add(money("2128 Premiums received in advance = sum of policy balances", ledger(trial, PREMIUMS_IN_ADVANCE),
            billing.policyBalancesTotal()));

        // Policy by policy: the ledger's receivable beside what that policy's invoices still owe. A policy with no
        // receivable posting (an IFRS 9 contract's invoice posts none) is not compared.
        Map<String, BigDecimal> receivable = finaccounting.receivablesByPolicy();
        Map<String, BigDecimal> owed = billing.owedByPolicy();
        BigDecimal ledgerTotal = BigDecimal.ZERO;
        BigDecimal owedTotal = BigDecimal.ZERO;
        long differing = 0;
        for (Map.Entry<String, BigDecimal> e : receivable.entrySet()) {
            BigDecimal o = owed.getOrDefault(e.getKey(), BigDecimal.ZERO);
            ledgerTotal = ledgerTotal.add(e.getValue());
            owedTotal = owedTotal.add(o);
            if (e.getValue().compareTo(o) != 0) differing++;
        }
        checks.add(money("2122 + 2142 Premiums receivable = what invoices still owe", ledgerTotal, owedTotal));
        checks.add(count("Policies whose receivable differs from what their invoices owe", differing, 0));

        // Cash by account: the ledger beside the money the rail actually moved, mapped by method exactly as postings are.
        Map<String, BigDecimal> moved = new HashMap<>();
        for (PaymentApi.MovedByMethod m : payments.movedByMethod()) {
            boolean in = "IN".equals(m.direction());
            String account = in ? CashAccounts.forReceipt(m.method()) : CashAccounts.forPayout(m.method());
            moved.merge(account, in ? m.total() : m.total().negate(), BigDecimal::add);
        }
        for (String account : List.of(CashAccounts.COLLECTION_BANK, CashAccounts.PAYOUT_BANK, CashAccounts.MOBILE_MONEY)) {
            checks.add(money(account + " cash = money confirmed in less money paid out", ledger(trial, account),
                moved.getOrDefault(account, BigDecimal.ZERO)));
        }

        checks.add(money("Trial balance: debits = credits", trial.totalDebit(), trial.totalCredit()));
        checks.add(count("Money events left unposted", postingQueue.unpostedEvents(true).size(), 0));
        return new BooksMatchView(checks.stream().allMatch(BooksMatchView.Check::matches), List.copyOf(checks));
    }

    /** An account's own balance, netted in its normal direction; zero for one never posted to. */
    private static BigDecimal ledger(TrialBalanceView trial, String account) {
        return trial.accounts().stream().filter(a -> account.equals(a.accountCode()))
            .map(AccountBalanceView::balance).findFirst().orElse(BigDecimal.ZERO);
    }

    private static BooksMatchView.Check money(String name, BigDecimal ledger, BigDecimal records) {
        BigDecimal l = ledger.setScale(2, RoundingMode.HALF_UP);
        BigDecimal r = records.setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = l.subtract(r);
        return new BooksMatchView.Check(name, l.toPlainString(), r.toPlainString(), difference.toPlainString(), "TZS",
            difference.signum() == 0);
    }

    private static BooksMatchView.Check count(String name, long ledger, long records) {
        return new BooksMatchView.Check(name, Long.toString(ledger), Long.toString(records),
            Long.toString(ledger - records), "count", ledger == records);
    }
}
