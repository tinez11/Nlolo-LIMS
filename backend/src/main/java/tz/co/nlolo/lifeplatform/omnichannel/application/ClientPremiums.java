package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.omnichannel.api.ClientPremiumsView;
import tz.co.nlolo.lifeplatform.omnichannel.api.ClientPremiumsView.LastReceipt;
import tz.co.nlolo.lifeplatform.omnichannel.api.ClientPremiumsView.NotInvoiced;
import tz.co.nlolo.lifeplatform.omnichannel.api.ClientPremiumsView.Row;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Column;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Field;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Kind;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.HeldPolicy;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a client owes across every policy they hold, and their last receipt (2026-11-12, the client Premiums tab; the
 * user: "to see the upcoming invoices for their policies so it becomes quick for payments"). Read from the modules
 * that own each part -- the policies from policy, the invoices from billing, the receipts from payment -- and summed
 * here, so the screen adds nothing up.
 *
 * <p>Invoices are raised a year ahead, so "upcoming" is real invoices. A policy with nothing raised ahead says how it
 * is paid instead, from billing's own facts: a single premium that once had its invoice is paid ONCE; a single-premium
 * contract that never had one is billed PER_ENROLMENT_FILE (a lender's scheme -- billing raises nothing against it).
 */
@Service
public class ClientPremiums {

    /** The windows the tab offers; overdue is always included. */
    public static final Set<String> WINDOWS = Set.of("30", "90", "ALL");
    static final String CURRENCY = "TZS";
    private static final Set<InvoiceStatus> OPEN =
        EnumSet.of(InvoiceStatus.DUE, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.IN_GRACE, InvoiceStatus.OVERDUE);

    private final PolicyApi policyApi;
    private final BillingApi billingApi;
    private final PaymentApi paymentApi;
    private final ProductApi productApi;
    private final PartyApi partyApi;

    public ClientPremiums(PolicyApi policyApi, BillingApi billingApi, PaymentApi paymentApi, ProductApi productApi,
                          PartyApi partyApi) {
        this.policyApi = policyApi;
        this.billingApi = billingApi;
        this.paymentApi = paymentApi;
        this.productApi = productApi;
        this.partyApi = partyApi;
    }

    public ClientPremiumsView read(UUID partyId, String window, LocalDate today) {
        if (!WINDOWS.contains(window)) {
            throw new IllegalArgumentException("window is 30, 90 or ALL, not " + window);
        }
        LocalDate until = "ALL".equals(window) ? null : today.plusDays(Long.parseLong(window));
        Map<UUID, String> productNames = new HashMap<>();

        List<Row> overdue = new ArrayList<>();
        List<Row> coming = new ArrayList<>();
        List<NotInvoiced> notInvoiced = new ArrayList<>();
        LastReceipt last = null;
        Row next = null;

        // Two reads for the whole book, not two per policy: 176 policies took 29 seconds one by one (2026-11-12).
        List<HeldPolicy> held = policyApi.policiesHeldBy(partyId);
        List<String> numbers = held.stream().map(HeldPolicy::policyNumber).toList();
        Map<String, List<InvoiceView>> invoicesByPolicy = new HashMap<>();
        for (InvoiceView i : billingApi.invoicesForPolicies(numbers)) {
            invoicesByPolicy.computeIfAbsent(i.policyNumber(), k -> new ArrayList<>()).add(i);
        }
        Map<String, List<ReceiptView>> receiptsByPolicy = new HashMap<>();
        for (ReceiptView r : paymentApi.receiptsForPolicies(numbers)) {
            receiptsByPolicy.computeIfAbsent(r.policyNumber(), k -> new ArrayList<>()).add(r);
        }

        for (HeldPolicy policy : held) {
            String product = productNames.computeIfAbsent(policy.productId(), this::productName);
            List<InvoiceView> invoices = invoicesByPolicy.getOrDefault(policy.policyNumber(), List.of());
            boolean anyAhead = false;
            for (InvoiceView i : invoices) {
                if (!OPEN.contains(i.status()) || i.balanceDue() == null || i.balanceDue().signum() <= 0) continue;
                anyAhead = true;
                Row row = new Row(i.invoiceId(), policy.policyNumber(), product, i.dueDate(), i.balanceDue(),
                    i.currency(), status(i, today));
                if ("OVERDUE".equals(row.status())) {
                    overdue.add(row);
                    continue;
                }
                // Next due is a date still ahead: one past due but in its grace is owed now, not "next".
                if (!row.dueDate().isBefore(today) && (next == null || row.dueDate().isBefore(next.dueDate()))) next = row;
                if (until == null || !row.dueDate().isAfter(until)) coming.add(row);
            }
            if (!anyAhead && "SINGLE".equals(policy.premiumFrequency())) {
                notInvoiced.add(new NotInvoiced(policy.policyNumber(), product,
                    invoices.isEmpty() ? "PER_ENROLMENT_FILE" : "ONCE"));
            }
            for (ReceiptView r : receiptsByPolicy.getOrDefault(policy.policyNumber(), List.of())) {
                if (!"CONFIRMED".equals(r.status()) || r.receiptNumber() == null) continue;
                LocalDate on = receivedOn(r);
                if (last == null || on.isAfter(last.receivedOn())) {
                    last = new LastReceipt(r.receiptId(), r.receiptNumber(), policy.policyNumber(), on, r.amount(),
                        r.currency());
                }
            }
        }

        overdue.sort(Comparator.comparing(Row::dueDate).thenComparing(Row::policyNumber));
        coming.sort(Comparator.comparing(Row::dueDate).thenComparing(Row::policyNumber));
        List<Row> rows = new ArrayList<>(overdue);
        rows.addAll(coming);
        notInvoiced.sort(Comparator.comparing(NotInvoiced::policyNumber));

        return new ClientPremiumsView(window,
            overdue.size(), sum(overdue),
            next == null ? null : next.dueDate(), next == null ? null : next.owed(),
            coming.size(), sum(coming),
            CURRENCY, last, rows, notInvoiced);
    }

    /** The same, as a file to keep or hand over: one row per invoice, the three figures as totals. */
    public CustomerDocument document(UUID partyId, String window, LocalDate today) {
        ClientPremiumsView v = read(partyId, window, today);
        String c = v.currency();
        List<Column> columns = List.of(new Column("Due date", Kind.DATE, 1.1f), new Column("Policy", Kind.TEXT, 1.4f),
            new Column("Product", Kind.TEXT, 1.8f), new Column("Owed (" + c + ")", Kind.MONEY, 1.3f),
            new Column("Status", Kind.TEXT, 1.1f));
        List<List<Object>> rows = v.rows().stream().map(r -> List.<Object>of(r.dueDate(), r.policyNumber(),
            r.productName() == null ? "-" : r.productName(), r.owed(), words(r.status()))).toList();
        List<Field> totals = new ArrayList<>(List.of(
            new Field("Overdue now", PolicyDocuments.money(v.overdueOwed(), c)),
            new Field("Due " + windowWords(window), PolicyDocuments.money(v.dueInWindowOwed(), c))));
        if (v.nextDueOn() != null) {
            totals.add(new Field("Next payment", PolicyDocuments.money(v.nextDueAmount(), c) + " due "
                + PolicyDocuments.DMY.format(v.nextDueOn())));
        }
        List<String> notes = new ArrayList<>();
        notes.add("Every policy this client holds. Overdue premiums first, then by due date.");
        for (NotInvoiced n : v.notInvoicedAhead()) {
            notes.add(n.policyNumber() + ("ONCE".equals(n.how()) ? " is paid once" : " is paid per enrolment file")
                + " -- nothing is raised ahead.");
        }
        List<Field> header = List.of(new Field("Client", clientName(partyId)),
            new Field("Showing", "Overdue, and " + windowWords(window)),
            new Field("Printed on", PolicyDocuments.DMY.format(today)));
        return new CustomerDocument(PolicyDocuments.ISSUER, "Premiums due", header, columns, rows, totals, notes, false);
    }

    /** OVERDUE once past due and its grace (billing's own status), otherwise billing's status as it stands. */
    private static String status(InvoiceView i, LocalDate today) {
        if (i.status() == InvoiceStatus.OVERDUE) return "OVERDUE";
        if (i.status() == InvoiceStatus.IN_GRACE) return "IN_GRACE";
        if (i.dueDate().isBefore(today) && (i.gracePeriodEndsAt() == null || i.gracePeriodEndsAt().isBefore(today))) {
            return "OVERDUE";
        }
        return i.status().name();
    }

    private static BigDecimal sum(List<Row> rows) {
        return rows.stream().filter(r -> CURRENCY.equals(r.currency())).map(Row::owed)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static LocalDate receivedOn(ReceiptView r) {
        if (r.receivedOn() != null) return r.receivedOn();
        return (r.decidedAt() != null ? r.decidedAt() : r.recordedAt()).atZone(PolicyDocuments.CIVIL_ZONE).toLocalDate();
    }

    private String productName(UUID productId) {
        try {
            return productApi.getProduct(productId).productName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String clientName(UUID partyId) {
        try {
            return partyApi.getParty(partyId).displayName();
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private static String windowWords(String window) {
        return "ALL".equals(window) ? "on every invoice raised" : "in the next " + window + " days";
    }

    private static String words(String status) {
        return switch (status) {
            case "OVERDUE" -> "Overdue";
            case "IN_GRACE" -> "In grace";
            case "PARTIALLY_PAID" -> "Part paid";
            default -> "Due";
        };
    }
}
