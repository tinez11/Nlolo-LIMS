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
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi.MovedByMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BooksMatchTest {

    private final FinaccountingApi finaccounting = mock(FinaccountingApi.class);
    private final PostingQueueApi postingQueue = mock(PostingQueueApi.class);
    private final BillingApi billing = mock(BillingApi.class);
    private final PaymentApi payments = mock(PaymentApi.class);
    private final BooksMatch booksMatch = new BooksMatch(finaccounting, postingQueue, billing, payments);

    private static AccountBalanceView account(String code, String balance) {
        BigDecimal b = new BigDecimal(balance);
        return new AccountBalanceView(code, code, AccountType.LIABILITY, PostingDirection.CR, null, (short) 3, true,
            AccountStatus.ACTIVE, "TZS", BigDecimal.ZERO, b, BigDecimal.ZERO, b, b);
    }

    /** A tenant whose ledger is given, against records that are always the same. */
    private void ledger(String in2128, String cash1120, String cash1130, String cash1140, Map<String, BigDecimal> receivable,
                        int unposted) {
        when(finaccounting.trialBalance(null)).thenReturn(new TrialBalanceView(null, List.of(
            account("1120", cash1120), account("1130", cash1130), account("1140", cash1140), account("2128", in2128)),
            new BigDecimal("500000.00"), new BigDecimal("500000.00"), true));
        when(finaccounting.receivablesByPolicy()).thenReturn(receivable);
        when(billing.policyBalancesTotal()).thenReturn(new BigDecimal("120000.00"));
        // An IFRS 9 policy owes 5,000 too, but has no receivable posting, so it is not compared.
        when(billing.owedByPolicy()).thenReturn(Map.of("POL-A", new BigDecimal("30000.00"),
            "POL-B", new BigDecimal("10000.00"), "POL-SAVE", new BigDecimal("5000.00")));
        // 200,000 by cheque and 50,000 by mobile money in; 40,000 by bank transfer and 15,000 by mobile money out.
        when(payments.movedByMethod()).thenReturn(List.of(
            new MovedByMethod("IN", "CHEQUE", new BigDecimal("200000.00")),
            new MovedByMethod("IN", null, new BigDecimal("50000.00")),
            new MovedByMethod("OUT", "EFT", new BigDecimal("40000.00")),
            new MovedByMethod("OUT", "MOBILE_MONEY", new BigDecimal("15000.00"))));
        List<UnpostedEventView> open = IntStream.range(0, unposted).mapToObj(i -> mock(UnpostedEventView.class)).toList();
        when(postingQueue.unpostedEvents(true)).thenReturn(open);
    }

    private static BooksMatchView.Check named(BooksMatchView view, String prefix) {
        return view.checks().stream().filter(c -> c.name().startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void booksThatMatchSayZeroEverywhere() {
        ledger("120000", "200000", "-40000", "35000",
            Map.of("POL-A", new BigDecimal("30000.00"), "POL-B", new BigDecimal("10000.00")), 0);

        BooksMatchView view = booksMatch.check();

        assertThat(view.allMatch()).isTrue();
        assertThat(view.checks()).allSatisfy(c -> assertThat(c.difference()).isIn("0.00", "0"));
        assertThat(named(view, "2122 + 2142").records()).as("the savings policy's 5,000 is not a receivable")
            .isEqualTo("40000.00");
        assertThat(named(view, "1130").records()).isEqualTo("-40000.00");
    }

    @Test
    void eachDifferenceLandsOnItsOwnCheckAndOnlyThoseFail() {
        // POL-B's ledger receivable is 4,000 where its invoices owe 10,000, a bank transfer out never posted, and one
        // event waits unposted.
        ledger("120000", "200000", "0", "35000",
            Map.of("POL-A", new BigDecimal("30000.00"), "POL-B", new BigDecimal("4000.00")), 1);

        BooksMatchView view = booksMatch.check();

        assertThat(view.allMatch()).isFalse();
        assertThat(view.checks()).filteredOn(c -> !c.matches()).extracting(BooksMatchView.Check::name)
            .containsExactly("2122 + 2142 Premiums receivable = what invoices still owe",
                "Policies whose receivable differs from what their invoices owe",
                "1130 cash = money confirmed in less money paid out",
                "Money events left unposted");
        assertThat(named(view, "2122 + 2142").difference()).isEqualTo("-6000.00");
        assertThat(named(view, "Policies whose").ledger()).isEqualTo("1");
        assertThat(named(view, "1130").difference()).isEqualTo("40000.00");
    }
}
