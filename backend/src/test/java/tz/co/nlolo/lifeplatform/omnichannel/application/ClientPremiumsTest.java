package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.omnichannel.api.ClientPremiumsView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.HeldPolicy;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private static HeldPolicy policy(String number, String frequency) {
        return new HeldPolicy(number, UUID.nameUUIDFromBytes(number.getBytes()), frequency);
    }

    private static InvoiceView invoice(String policy, String due, String owed, InvoiceStatus status) {
        BigDecimal amount = new BigDecimal(owed);
        return new InvoiceView(UUID.randomUUID(), policy, LocalDate.parse(due), amount, "TZS", status, null, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, status == InvoiceStatus.PAID ? BigDecimal.ZERO : amount, null, null, null);
    }

    private static ReceiptView receipt(String policy, String number, String on) {
        ReceiptView r = mock(ReceiptView.class);
        when(r.policyNumber()).thenReturn(policy);
        when(r.receiptId()).thenReturn(UUID.randomUUID());
        when(r.receiptNumber()).thenReturn(number);
        when(r.status()).thenReturn("CONFIRMED");
        when(r.receivedOn()).thenReturn(LocalDate.parse(on));
        when(r.amount()).thenReturn(new BigDecimal("80000.00"));
        when(r.currency()).thenReturn("TZS");
        return r;
    }

    private void book() {
        when(policies.policiesHeldBy(PARTY)).thenReturn(List.of(policy("POL-A", "MONTHLY"), policy("POL-B", "MONTHLY"),
            policy("CRL-1", "SINGLE"), policy("POL-S", "SINGLE")));
        // In grace on 10 Nov: past due, owed now -- never the "next due".
        List<InvoiceView> invoices = List.of(
            invoice("POL-A", "2026-10-12", "80000.00", InvoiceStatus.OVERDUE),
            invoice("POL-A", "2026-11-01", "80000.00", InvoiceStatus.PAID),
            invoice("POL-B", "2026-11-10", "15000.00", InvoiceStatus.IN_GRACE),
            invoice("POL-B", "2026-11-20", "15000.00", InvoiceStatus.DUE),
            invoice("POL-A", "2026-12-01", "80000.00", InvoiceStatus.DUE),
            invoice("POL-A", "2027-02-01", "80000.00", InvoiceStatus.DUE),
            invoice("POL-S", "2026-01-05", "500000.00", InvoiceStatus.PAID));
        when(billing.invoicesForPolicies(any())).thenReturn(invoices);
        ReceiptView newest = receipt("POL-A", "RC-2026-000123", "2026-11-05");
        ReceiptView older = receipt("POL-B", "RC-2026-000099", "2026-10-01");
        when(payments.receiptsForPolicies(any())).thenReturn(List.of(newest, older));
    }

    @Test
    void overdueFirstThenByDateWithinTheWindowAndTheNewestReceipt() {
        book();

        ClientPremiumsView v = premiums.read(PARTY, "30", TODAY);

        assertThat(v.overdueCount()).isEqualTo(1);
        assertThat(v.overdueOwed()).isEqualByComparingTo("80000.00");
        assertThat(v.nextDueOn()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(v.nextDueAmount()).isEqualByComparingTo("15000.00");
        // In grace (10 Nov), 20 Nov and 1 Dec are owed within 30 days of 12 Nov; 1 Feb is not, and the paid one is not owed.
        assertThat(v.dueInWindowCount()).isEqualTo(3);
        assertThat(v.dueInWindowOwed()).isEqualByComparingTo("110000.00");
        assertThat(v.rows()).extracting(r -> r.policyNumber() + "@" + r.dueDate() + ":" + r.status())
            .containsExactly("POL-A@2026-10-12:OVERDUE", "POL-B@2026-11-10:IN_GRACE", "POL-B@2026-11-20:DUE",
                "POL-A@2026-12-01:DUE");
        assertThat(v.lastReceipt().receiptNumber()).isEqualTo("RC-2026-000123");
        assertThat(v.lastReceipt().policyNumber()).isEqualTo("POL-A");
        assertThat(v.notInvoicedAhead()).extracting(n -> n.policyNumber() + ":" + n.how())
            .containsExactly("CRL-1:PER_ENROLMENT_FILE", "POL-S:ONCE");
    }

    @Test
    void everyInvoiceRaisedWhenAskedForAll() {
        book();

        ClientPremiumsView v = premiums.read(PARTY, "ALL", TODAY);

        assertThat(v.dueInWindowCount()).isEqualTo(4);
        assertThat(v.rows()).hasSize(5);
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
        assertThat(document.rows()).hasSize(4);
        assertThat(document.totals()).extracting(f -> f.label()).contains("Overdue now", "Due in the next 30 days",
            "Next payment");
        assertThat(document.notes()).anyMatch(n -> n.startsWith("CRL-1 is paid per enrolment file"));
    }
}
