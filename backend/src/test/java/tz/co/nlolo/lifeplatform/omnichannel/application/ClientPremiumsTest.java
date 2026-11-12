package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.omnichannel.api.ClientPremiumsView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The client Premiums tab (2026-11-12): every policy at once, overdue first, the last receipt across them all. */
class ClientPremiumsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-12");
    private static final UUID PARTY = UUID.randomUUID();

    private final PolicyApi policies = mock(PolicyApi.class);
    private final BillingApi billing = mock(BillingApi.class);
    private final PaymentApi payments = mock(PaymentApi.class);
    private final ProductApi products = mock(ProductApi.class);
    private final PartyApi parties = mock(PartyApi.class);
    private final ClientPremiums premiums = new ClientPremiums(policies, billing, payments, products, parties);

    private static PolicyView policy(String number, String frequency) {
        PolicyView p = mock(PolicyView.class);
        when(p.policyNumber()).thenReturn(number);
        when(p.premiumFrequency()).thenReturn(frequency);
        when(p.productId()).thenReturn(UUID.nameUUIDFromBytes(number.getBytes()));
        return p;
    }

    private static InvoiceView invoice(String policy, String due, String owed, InvoiceStatus status) {
        BigDecimal amount = new BigDecimal(owed);
        return new InvoiceView(UUID.randomUUID(), policy, LocalDate.parse(due), amount, "TZS", status, null, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, status == InvoiceStatus.PAID ? BigDecimal.ZERO : amount, null, null, null);
    }

    private static ReceiptView receipt(String number, String on) {
        ReceiptView r = mock(ReceiptView.class);
        when(r.receiptId()).thenReturn(UUID.randomUUID());
        when(r.receiptNumber()).thenReturn(number);
        when(r.status()).thenReturn("CONFIRMED");
        when(r.receivedOn()).thenReturn(LocalDate.parse(on));
        when(r.amount()).thenReturn(new BigDecimal("80000.00"));
        when(r.currency()).thenReturn("TZS");
        return r;
    }

    private void book() {
        PolicyView a = policy("POL-A", "MONTHLY");
        PolicyView b = policy("POL-B", "MONTHLY");
        PolicyView scheme = policy("CRL-1", "SINGLE");
        PolicyView single = policy("POL-S", "SINGLE");
        when(policies.searchPolicies(eq(PARTY), isNull(), isNull(), isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(a, b, scheme, single)));
        when(billing.listInvoices("POL-A", null)).thenReturn(List.of(
            invoice("POL-A", "2026-10-12", "80000.00", InvoiceStatus.OVERDUE),
            invoice("POL-A", "2026-11-01", "80000.00", InvoiceStatus.PAID),
            invoice("POL-A", "2026-12-01", "80000.00", InvoiceStatus.DUE),
            invoice("POL-A", "2027-02-01", "80000.00", InvoiceStatus.DUE)));
        when(billing.listInvoices("POL-B", null)).thenReturn(List.of(
            invoice("POL-B", "2026-11-20", "15000.00", InvoiceStatus.DUE)));
        when(billing.listInvoices("CRL-1", null)).thenReturn(List.of());
        when(billing.listInvoices("POL-S", null)).thenReturn(List.of(
            invoice("POL-S", "2026-01-05", "500000.00", InvoiceStatus.PAID)));
        ReceiptView newest = receipt("RC-2026-000123", "2026-11-05");
        ReceiptView older = receipt("RC-2026-000099", "2026-10-01");
        when(payments.receiptsForPolicy("POL-A")).thenReturn(List.of(newest));
        when(payments.receiptsForPolicy("POL-B")).thenReturn(List.of(older));
    }

    @Test
    void overdueFirstThenByDateWithinTheWindowAndTheNewestReceipt() {
        book();

        ClientPremiumsView v = premiums.read(PARTY, "30", TODAY);

        assertThat(v.overdueCount()).isEqualTo(1);
        assertThat(v.overdueOwed()).isEqualByComparingTo("80000.00");
        assertThat(v.nextDueOn()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(v.nextDueAmount()).isEqualByComparingTo("15000.00");
        // 20 Nov and 1 Dec are within 30 days of 12 Nov; 1 Feb is not, and the paid one is not owed.
        assertThat(v.dueInWindowCount()).isEqualTo(2);
        assertThat(v.dueInWindowOwed()).isEqualByComparingTo("95000.00");
        assertThat(v.rows()).extracting(r -> r.policyNumber() + "@" + r.dueDate() + ":" + r.status())
            .containsExactly("POL-A@2026-10-12:OVERDUE", "POL-B@2026-11-20:DUE", "POL-A@2026-12-01:DUE");
        assertThat(v.lastReceipt().receiptNumber()).isEqualTo("RC-2026-000123");
        assertThat(v.lastReceipt().policyNumber()).isEqualTo("POL-A");
        assertThat(v.notInvoicedAhead()).extracting(n -> n.policyNumber() + ":" + n.how())
            .containsExactly("CRL-1:PER_ENROLMENT_FILE", "POL-S:ONCE");
    }

    @Test
    void everyInvoiceRaisedWhenAskedForAll() {
        book();

        ClientPremiumsView v = premiums.read(PARTY, "ALL", TODAY);

        assertThat(v.dueInWindowCount()).isEqualTo(3);
        assertThat(v.rows()).hasSize(4);
    }

    @Test
    void anUnknownWindowIsRefused() {
        assertThatThrownBy(() -> premiums.read(PARTY, "7", TODAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFileListsTheRowsAndTheTotals() {
        book();
        when(parties.getParty(PARTY)).thenReturn(
            new tz.co.nlolo.lifeplatform.party.api.PartyView(PARTY, null, null, "Amina Owner"));

        var document = premiums.document(PARTY, "30", TODAY);

        assertThat(document.title()).isEqualTo("Premiums due");
        assertThat(document.rows()).hasSize(3);
        assertThat(document.totals()).extracting(f -> f.label()).contains("Overdue now", "Due in the next 30 days",
            "Next payment");
        assertThat(document.notes()).anyMatch(n -> n.startsWith("CRL-1 is paid per enrolment file"));
    }
}
