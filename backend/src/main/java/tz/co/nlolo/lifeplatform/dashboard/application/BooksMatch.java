package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.BooksMatchView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingQueueApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Do the books match? (money movement step 7, design §12.) Run after seeding and after every money test; it compares
 * the ledger with the records it summarises and says by how much each differs:
 *
 * <ul>
 *   <li>2128 Premiums received in advance against the sum of every policy balance;</li>
 *   <li>the trial balance: debits equal credits;</li>
 *   <li>no money event left unposted (the posting queue's open items).</li>
 * </ul>
 *
 * Here and not in finaccounting because it reads billing too, and billing already reaches finaccounting through policy
 * and distribution: a check owned by either would close a cycle. The dashboard module reads both and nothing reads it.
 *
 * <p>Not yet here: the receivables (2122/2142) against what invoices still owe, which needs each policy's measurement
 * model, and cash by method (1120/1140) against confirmed deposits less withdrawals.
 */
@Service
public class BooksMatch {

    static final String PREMIUMS_IN_ADVANCE = "2128";

    private final FinaccountingApi finaccounting;
    private final PostingQueueApi postingQueue;
    private final BillingApi billing;

    public BooksMatch(FinaccountingApi finaccounting, PostingQueueApi postingQueue, BillingApi billing) {
        this.finaccounting = finaccounting;
        this.postingQueue = postingQueue;
        this.billing = billing;
    }

    public BooksMatchView check() {
        TrialBalanceView trial = finaccounting.trialBalance(null);
        BigDecimal inAdvance = trial.accounts().stream().filter(a -> PREMIUMS_IN_ADVANCE.equals(a.accountCode()))
            .map(AccountBalanceView::balance).findFirst().orElse(BigDecimal.ZERO);
        List<BooksMatchView.Check> checks = List.of(
            money("2128 Premiums received in advance = sum of policy balances", inAdvance, billing.policyBalancesTotal()),
            money("Trial balance: debits = credits", trial.totalDebit(), trial.totalCredit()),
            count("Money events left unposted", postingQueue.unpostedEvents(true).size(), 0));
        return new BooksMatchView(checks.stream().allMatch(BooksMatchView.Check::matches), checks);
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
