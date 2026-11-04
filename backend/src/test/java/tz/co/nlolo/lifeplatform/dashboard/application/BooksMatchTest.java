package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.BooksMatchView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingQueueApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventView;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BooksMatchTest {

    private final FinaccountingApi finaccounting = mock(FinaccountingApi.class);
    private final PostingQueueApi postingQueue = mock(PostingQueueApi.class);
    private final BillingApi billing = mock(BillingApi.class);
    private final BooksMatch booksMatch = new BooksMatch(finaccounting, postingQueue, billing);

    private static AccountBalanceView account(String code, String balance) {
        BigDecimal b = new BigDecimal(balance);
        return new AccountBalanceView(code, "Premiums received in advance", AccountType.LIABILITY, PostingDirection.CR,
            null, (short) 3, true, AccountStatus.ACTIVE, "TZS", BigDecimal.ZERO, b, BigDecimal.ZERO, b, b);
    }

    private void books(String in2128, String debits, String credits, String balances, int unposted) {
        when(finaccounting.trialBalance(null)).thenReturn(new TrialBalanceView(null,
            List.of(account("1120", "900.00"), account("2128", in2128)), new BigDecimal(debits), new BigDecimal(credits),
            debits.equals(credits)));
        when(billing.policyBalancesTotal()).thenReturn(new BigDecimal(balances));
        List<UnpostedEventView> open = java.util.stream.IntStream.range(0, unposted)
            .mapToObj(i -> mock(UnpostedEventView.class)).toList();
        when(postingQueue.unpostedEvents(true)).thenReturn(open);
    }

    @Test
    void booksThatMatchSayZeroEverywhere() {
        books("120000", "500000.00", "500000.00", "120000.00", 0);

        BooksMatchView view = booksMatch.check();

        assertThat(view.allMatch()).isTrue();
        assertThat(view.checks()).extracting(BooksMatchView.Check::difference).containsExactly("0.00", "0.00", "0");
        assertThat(view.checks().get(0).ledger()).isEqualTo("120000.00");
    }

    @Test
    void aBalanceTheLedgerNeverHeardOfIsTheDifferenceAndOnlyThatCheckFails() {
        // A deposit on a policy's balance whose DepositReceived never posted: the records hold 30,000 more than 2128.
        books("90000.00", "500000.00", "500000.00", "120000.00", 1);

        BooksMatchView view = booksMatch.check();

        assertThat(view.allMatch()).isFalse();
        assertThat(view.checks()).extracting(BooksMatchView.Check::matches).containsExactly(false, true, false);
        assertThat(view.checks().get(0).difference()).isEqualTo("-30000.00");
        assertThat(view.checks().get(2)).extracting(BooksMatchView.Check::ledger, BooksMatchView.Check::unit)
            .containsExactly("1", "count");
    }
}
