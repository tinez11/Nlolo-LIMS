package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.billing.api.ReceiptView;
import tz.co.nlolo.lifeplatform.omnichannel.api.PaymentScheduleView;
import tz.co.nlolo.lifeplatform.omnichannel.api.SavingsStatementView;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Column;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Field;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Kind;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentPdf;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The documents a customer can ask for (2026-10-07): a policy's payment schedule, and a savings plan's account
 * statement. Each is built once as a view (what the console shows) and once as a {@link CustomerDocument} (what
 * the PDF and Excel downloads render), from the same rows.
 */
@Service
public class PolicyDocuments {

    /** The issuer printed at the top of every document, until the company's letterhead is supplied. */
    static final String ISSUER = "Nlolo Life";
    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PolicyApi policyApi;
    private final BillingApi billingApi;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final AccumulationApi accumulationApi;

    public PolicyDocuments(PolicyApi policyApi, BillingApi billingApi, PartyApi partyApi, ProductApi productApi,
                           AccumulationApi accumulationApi) {
        this.policyApi = policyApi;
        this.billingApi = billingApi;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.accumulationApi = accumulationApi;
    }

    // ---- the payment schedule ----

    @Transactional(readOnly = true)
    public PaymentScheduleView paymentSchedule(String policyNumber) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        LocalDate today = LocalDate.now(CIVIL_ZONE);
        Map<UUID, List<ReceiptView>> receipts = billingApi.listReceipts(policyNumber).stream()
            .collect(Collectors.groupingBy(ReceiptView::invoiceId));
        List<InvoiceView> invoices = billingApi.listInvoices(policyNumber, null).stream()
            .sorted(Comparator.comparing(InvoiceView::dueDate)).toList();

        List<PaymentScheduleView.Line> lines = new ArrayList<>();
        BigDecimal charged = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal upcoming = BigDecimal.ZERO;
        LocalDate nextDue = null;
        BigDecimal nextAmount = null;
        int number = 1;
        for (InvoiceView i : invoices) {
            List<ReceiptView> paid = receipts.getOrDefault(i.invoiceId(), List.of());
            boolean waived = i.status() == InvoiceStatus.WAIVED;
            BigDecimal balance = waived ? BigDecimal.ZERO : nz(i.balanceDue());
            if (!waived) charged = charged.add(i.amount());
            if (balance.signum() > 0) {
                if (i.dueDate().isAfter(today)) upcoming = upcoming.add(balance); else outstanding = outstanding.add(balance);
                if (nextDue == null) { nextDue = i.dueDate(); nextAmount = balance; }
            }
            lines.add(new PaymentScheduleView.Line(number++, i.invoiceId(), i.dueDate(), i.amount(), nz(i.amountPaid()),
                paid.stream().map(r -> r.receivedAt().atZone(CIVIL_ZONE).toLocalDate()).max(Comparator.naturalOrder()).orElse(null),
                joined(paid.stream().map(ReceiptView::paymentReference).toList()),
                joined(paid.stream().map(ReceiptView::payerRef).toList()),
                status(i, today), balance, i.coversFrom(), i.coversTo()));
        }
        BigDecimal received = invoices.stream().map(i -> nz(i.amountPaid())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaymentScheduleView(policyNumber, holderName(policy), productName(policy),
            policy.status() != null ? policy.status().name() : null, policy.premiumAmount(), policy.premiumFrequency(),
            policy.premiumCurrency(), policy.commencementDate() != null ? policy.commencementDate() : policy.issueDate(),
            lines, new PaymentScheduleView.Totals(charged, received, outstanding, upcoming, nextDue, nextAmount));
    }

    public CustomerDocument paymentScheduleDocument(String policyNumber) {
        PaymentScheduleView v = paymentSchedule(policyNumber);
        PolicyView policy = policyApi.getPolicy(policyNumber);
        String c = v.currency();
        List<Column> columns = List.of(new Column("No.", Kind.TEXT, 0.5f), new Column("Due date", Kind.DATE, 1.1f),
            new Column("Cover", Kind.TEXT, 1.9f),
            new Column("Amount due (" + c + ")", Kind.MONEY, 1.3f), new Column("Paid (" + c + ")", Kind.MONEY, 1.3f),
            new Column("Paid on", Kind.DATE, 1.1f), new Column("Receipt ref", Kind.TEXT, 1.6f),
            new Column("Paid by", Kind.TEXT, 1.4f), new Column("Status", Kind.TEXT, 1.0f),
            new Column("Balance (" + c + ")", Kind.MONEY, 1.3f));
        List<List<Object>> rows = v.lines().stream().map(l -> List.<Object>of(String.valueOf(l.number()), l.dueDate(),
            l.coversFrom() != null && l.coversTo() != null ? DMY.format(l.coversFrom()) + " - " + DMY.format(l.coversTo()) : "",
            l.amountDue(), l.amountPaid(), opt(l.paidOn()), opt(l.receipts()), opt(l.paidBy()), l.status(), l.balance())).toList();
        List<Field> totals = new ArrayList<>(List.of(
            new Field("Total charged", money(v.totals().charged(), c)),
            new Field("Total paid", money(v.totals().paid(), c)),
            new Field("Outstanding now", money(v.totals().outstanding(), c)),
            new Field("Still to come", money(v.totals().upcoming(), c))));
        if (v.totals().nextDueDate() != null) {
            totals.add(new Field("Next payment", money(v.totals().nextDueAmount(), c) + " due " + DMY.format(v.totals().nextDueDate())));
        }
        return new CustomerDocument(ISSUER, "Premium payment schedule", header(policy, v.productName(), List.of(
                new Field("Premium", money(v.premium(), c) + " " + frequency(v.premiumFrequency())),
                new Field("Cover from", v.coverStart() != null ? DMY.format(v.coverStart()) : "-"),
                new Field("Status", v.policyStatus()))),
            columns, rows, totals,
            List.of("A premium is paid when its balance is nil. Receipt references are the payment rail's own.",
                "Waived premiums were cancelled and are not owed."),
            true);
    }

    // ---- the savings statement ----

    @Transactional(readOnly = true)
    public SavingsStatementView savingsStatement(String policyNumber, LocalDate from, LocalDate to) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        StatementView s = accumulationApi.statement(policyNumber, from, to);
        List<LedgerEntryView> entries = s.groups().stream().flatMap(g -> g.entries().stream())
            .sorted(Comparator.comparingInt(LedgerEntryView::seq)).toList();
        List<SavingsStatementView.Line> lines = entries.stream().map(e -> new SavingsStatementView.Line(e.effectiveDate(),
            describe(e), e.amount().signum() >= 0 ? e.amount() : null, e.amount().signum() < 0 ? e.amount().negate() : null,
            e.balanceAfter())).toList();
        Map<EntryType, BigDecimal> byType = new EnumMap<>(EntryType.class);
        s.groups().forEach(g -> byType.merge(g.type(), g.total(), BigDecimal::add));
        List<SavingsStatementView.Total> totals = byType.entrySet().stream()
            .map(e -> new SavingsStatementView.Total(label(e.getKey()), e.getValue())).toList();
        return new SavingsStatementView(policyNumber, holderName(policy), productName(policy), s.currency(),
            s.periodFrom(), s.periodTo(), s.openingBalance(), s.closingBalance(), lines, totals);
    }

    public CustomerDocument savingsStatementDocument(String policyNumber, LocalDate from, LocalDate to) {
        SavingsStatementView v = savingsStatement(policyNumber, from, to);
        PolicyView policy = policyApi.getPolicy(policyNumber);
        String c = v.currency();
        List<Column> columns = List.of(new Column("Date", Kind.DATE, 1.0f), new Column("Description", Kind.TEXT, 3.2f),
            new Column("Money in (" + c + ")", Kind.MONEY, 1.3f), new Column("Money out (" + c + ")", Kind.MONEY, 1.3f),
            new Column("Balance (" + c + ")", Kind.MONEY, 1.4f));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(java.util.Arrays.asList(v.periodFrom(), "Opening balance", null, null, v.openingBalance()));
        v.lines().forEach(l -> rows.add(java.util.Arrays.asList(l.date(), l.description(), l.moneyIn(), l.moneyOut(), l.balance())));
        rows.add(java.util.Arrays.asList(v.periodTo(), "Closing balance", null, null, v.closingBalance()));
        List<Field> totals = new ArrayList<>();
        v.totals().forEach(t -> totals.add(new Field(t.label(), money(t.amount(), c))));
        totals.add(new Field("Closing balance", money(v.closingBalance(), c)));
        return new CustomerDocument(ISSUER, "Savings account statement", header(policy, v.productName(), List.of(
                new Field("Period", DMY.format(v.periodFrom()) + " to " + DMY.format(v.periodTo())),
                new Field("Opening balance", money(v.openingBalance(), c)))),
            columns, rows, totals,
            List.of("Money in and money out are shown as entered on the account; the balance is after each entry.",
                "A correction made after this statement appears on the next one."),
            false);
    }

    // ---- the policy schedule (2026-10-08, the customer portal design step 3) ----

    /**
     * What the policy is, in one page: who holds it and who it insures, what it pays and to whom, what it costs and
     * from when. A funeral plan lists each covered life with what their death pays; any other policy its sum assured
     * against the life insured; a savings or unit-linked plan adds what it is worth today. A summary -- the notes say
     * the policy terms govern.
     */
    @Transactional(readOnly = true)
    public CustomerDocument policyScheduleDocument(String policyNumber) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        String c = policy.premiumCurrency() != null ? policy.premiumCurrency() : policy.sumAssuredCurrency();
        String lifeInsured = policy.lifeAssuredPartyId() == null ? "-" : partyName(policy.lifeAssuredPartyId());
        List<Column> columns = List.of(new Column("Cover", Kind.TEXT, 2.0f), new Column("Life insured", Kind.TEXT, 2.4f),
            new Column("Pays (" + c + ")", Kind.MONEY, 1.4f));
        List<List<Object>> rows = new ArrayList<>();
        if ("FUNERAL".equals(policy.productCategory())) {
            // Lives still covered; on a policy whose cover has ended, every life it covered, each marked ended.
            var lives = policyApi.coveredLives(policyNumber);
            boolean anyCovered = lives.stream().anyMatch(l -> "ACTIVE".equals(l.status()));
            lives.stream().filter(l -> !anyCovered || "ACTIVE".equals(l.status()))
                .forEach(l -> rows.add(List.of("Death of " + roleLabel(l.role().name())
                    + ("ACTIVE".equals(l.status()) ? "" : " (cover ended)"), l.fullName(), l.benefit())));
        } else if (policy.sumAssuredAmount() != null && policy.sumAssuredAmount().signum() > 0) {
            rows.add(List.of("Death benefit", lifeInsured, policy.sumAssuredAmount()));
        }
        accumulationApi.findAccount(policyNumber).ifPresent(a ->
            rows.add(List.of("Account value today", lifeInsured, a.balance())));

        List<Field> extra = new ArrayList<>(List.of(
            new Field("Life insured", lifeInsured),
            new Field("Status", policy.status() != null ? statusWords(policy.status().name()) : "-"),
            new Field("Cover from", policy.commencementDate() != null ? DMY.format(policy.commencementDate())
                : policy.issueDate() != null ? DMY.format(policy.issueDate()) : "-"),
            new Field("Ends", policy.maturityDate() != null ? DMY.format(policy.maturityDate()) : "No fixed end date")));
        if (policy.premiumAmount() != null) {
            extra.add(new Field("Premium", money(policy.premiumAmount(), c) + " " + frequency(policy.premiumFrequency())));
        }
        List<Field> totals = new ArrayList<>();
        if (policy.premiumAmount() != null) {
            totals.add(new Field("Premium", money(policy.premiumAmount(), c) + " " + frequency(policy.premiumFrequency())));
        }
        List<String> notes = new ArrayList<>();
        String paidTo = policy.beneficiaries() == null ? "" : policy.beneficiaries().stream()
            .map(b -> (b.partyId() != null ? partyName(b.partyId()) : b.freeformDesignee())
                + (b.sharePercent() != null ? " (" + b.sharePercent().stripTrailingZeros().toPlainString() + "%)" : ""))
            .collect(Collectors.joining(", "));
        notes.add(paidTo.isBlank() ? "Beneficiaries: none named." : "Beneficiaries: " + paidTo + ".");
        if ("FUNERAL".equals(policy.productCategory())) {
            notes.add("Each covered life has its own waiting period from the day its cover started; an accident is covered from day one where the plan says so.");
        }
        notes.add("This schedule summarises the policy. The policy terms and conditions govern what is paid.");
        return new CustomerDocument(ISSUER, "Policy schedule", header(policy, productName(policy), extra), columns, rows,
            totals, notes, false);
    }

    // ---- premium receipts (2026-10-08, the customer portal design step 3) ----

    /** Every premium received on the policy, newest first, with the premium it paid. */
    @Transactional(readOnly = true)
    public List<tz.co.nlolo.lifeplatform.omnichannel.api.ReceiptLine> receipts(String policyNumber) {
        String frequency = policyApi.getPolicy(policyNumber).premiumFrequency();
        Map<UUID, InvoiceView> invoices = billingApi.listInvoices(policyNumber, null).stream()
            .collect(Collectors.toMap(InvoiceView::invoiceId, i -> i, (a, b) -> a));
        return billingApi.listReceipts(policyNumber).stream()
            .sorted(Comparator.comparing(ReceiptView::receivedAt).reversed())
            .map(r -> new tz.co.nlolo.lifeplatform.omnichannel.api.ReceiptLine(r.receiptId(),
                r.receivedAt().atZone(CIVIL_ZONE).toLocalDate(), r.amount(), r.currency(), r.paymentReference(),
                r.payerRef(), coversFrom(invoices.get(r.invoiceId()), frequency), coversTo(invoices.get(r.invoiceId()), frequency)))
            .toList();
    }

    /** One premium receipt, to keep or hand over. */
    @Transactional(readOnly = true)
    public CustomerDocument receiptDocument(String policyNumber, UUID receiptId) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        tz.co.nlolo.lifeplatform.omnichannel.api.ReceiptLine r = receipts(policyNumber).stream()
            .filter(x -> x.receiptId().equals(receiptId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Policy " + policyNumber + " has no receipt " + receiptId));
        List<Column> columns = List.of(new Column("Cover paid for", Kind.TEXT, 2.2f),
            new Column("Received on", Kind.DATE, 1.3f), new Column("Reference", Kind.TEXT, 1.9f),
            new Column("Amount (" + r.currency() + ")", Kind.MONEY, 1.4f));
        String period = r.coversFrom() != null ? DMY.format(r.coversFrom()) + " to " + DMY.format(r.coversTo()) : "";
        List<List<Object>> rows = List.of(java.util.Arrays.asList(period, r.receivedOn(), opt(r.reference()), r.amount()));
        return new CustomerDocument(ISSUER, "Premium receipt", header(policy, productName(policy), List.of(
                new Field("Received from", r.paidBy() != null ? r.paidBy() : "-"),
                new Field("Receipt reference", r.reference() != null ? r.reference() : r.receiptId().toString()))),
            columns, rows, List.of(new Field("Amount received", money(r.amount(), r.currency()))),
            List.of("Received with thanks. This receipt is issued by the platform from the payment as recorded."), false);
    }

    /**
     * The first day of cover an instalment buys. Billing dates it at the end of its period (premium in arrears), so the
     * period starts one frequency step before the due date. Null for a single premium or an unknown invoice.
     */
    private static LocalDate coversFrom(InvoiceView invoice, String frequency) {
        if (invoice == null) return null;
        if (invoice.coversFrom() != null) return invoice.coversFrom(); // recorded on the invoice since billing V11
        if (frequency == null) return null;
        return switch (frequency) {
            case "MONTHLY" -> invoice.dueDate().minusMonths(1);
            case "QUARTERLY" -> invoice.dueDate().minusMonths(3);
            case "ANNUALLY" -> invoice.dueDate().minusYears(1);
            default -> null;
        };
    }

    /** The last day of cover the instalment buys: recorded since billing V11, else the day before it fell due. */
    private static LocalDate coversTo(InvoiceView invoice, String frequency) {
        if (invoice == null) return null;
        if (invoice.coversTo() != null) return invoice.coversTo();
        return coversFrom(invoice, frequency) != null ? invoice.dueDate().minusDays(1) : null;
    }

    private String partyName(UUID partyId) {
        try {
            return partyApi.getParty(partyId).displayName();
        } catch (RuntimeException e) {
            return "-";
        }
    }

    /** "PAID_UP" as "Paid up". */
    private static String statusWords(String status) {
        String words = status.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "MAIN_MEMBER" -> "main member";
            case "SPOUSE" -> "spouse";
            case "CHILD" -> "child";
            case "PARENT" -> "parent";
            case "EXTENDED" -> "extended family member";
            default -> role.toLowerCase(java.util.Locale.ROOT);
        };
    }

    // ---- shared ----

    private List<Field> header(PolicyView policy, String productName, List<Field> extra) {
        PartyDetailView holder = holder(policy);
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Policyholder", holder != null ? holder.displayName() : "-"));
        fields.add(new Field("Phone", holder != null && holder.phoneNumber() != null ? holder.phoneNumber() : "-"));
        fields.add(new Field("Address", holder != null ? address(holder) : "-"));
        fields.add(new Field("Policy number", policy.policyNumber()));
        fields.add(new Field("Product", productName));
        fields.addAll(extra);
        fields.add(new Field("Printed on", DMY.format(LocalDate.now(CIVIL_ZONE))));
        return fields;
    }

    private PartyDetailView holder(PolicyView policy) {
        try {
            return policy.policyholderPartyId() != null ? partyApi.getPartyDetail(policy.policyholderPartyId()) : null;
        } catch (RuntimeException e) {
            return null; // a document still prints without the name rather than failing
        }
    }

    private String holderName(PolicyView policy) {
        PartyDetailView h = holder(policy);
        return h != null ? h.displayName() : null;
    }

    private String productName(PolicyView policy) {
        try {
            return productApi.getProduct(policy.productId()).productName();
        } catch (RuntimeException e) {
            return "-";
        }
    }

    private static String address(PartyDetailView p) {
        if (p.address() == null) return "-";
        String a = java.util.stream.Stream.of(p.address().line(), p.address().ward(), p.address().district(),
                p.address().region()).filter(Objects::nonNull).filter(s -> !s.isBlank()).collect(Collectors.joining(", "));
        return a.isBlank() ? "-" : a;
    }

    private static String status(InvoiceView i, LocalDate today) {
        return switch (i.status()) {
            case PAID -> "Paid";
            case PARTIALLY_PAID -> "Partly paid";
            case WAIVED -> "Waived";
            case OVERDUE -> "Overdue";
            case IN_GRACE -> "In grace";
            case DUE -> i.dueDate().isAfter(today) ? "Upcoming" : "Due";
        };
    }

    private static String frequency(String f) {
        if (f == null) return "";
        return switch (f) {
            case "MONTHLY" -> "a month";
            case "QUARTERLY" -> "a quarter";
            case "ANNUALLY" -> "a year";
            case "SINGLE" -> "once";
            default -> f.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String describe(LedgerEntryView e) {
        // The entry's own wording ("Top-up from +255...", "Allocation charge 5% (policy year 1)") already names it.
        String reason = e.reason();
        return reason != null && !reason.isBlank() ? reason : label(e.type());
    }

    static String label(EntryType t) {
        return switch (t) {
            case CONTRIBUTION -> "Premium";
            case TOP_UP -> "Top-up";
            case TRANSFER_IN -> "Transfer in";
            case ALLOCATION_CHARGE -> "Allocation charge";
            case POLICY_FEE -> "Policy fee";
            case INTEREST -> "Interest";
            case WITHDRAWAL -> "Withdrawal";
            case SURRENDER -> "Surrender";
            case MATURITY -> "Maturity";
            case VESTING -> "Pension vested";
            case DEATH_CLAIM -> "Death claim";
            case FREE_LOOK_REFUND -> "Free-look cancellation";
            case ADJUSTMENT -> "Adjustment";
            case REVERSAL -> "Reversal";
        };
    }

    private static String joined(List<String> values) {
        String s = new LinkedHashSet<>(values.stream().filter(Objects::nonNull).filter(v -> !v.isBlank()).toList())
            .stream().collect(Collectors.joining(", "));
        return s.isEmpty() ? null : s;
    }

    private static Object opt(Object v) {
        return v == null ? "" : v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String money(BigDecimal amount, String currency) {
        return amount == null ? "-" : currency + " " + DocumentPdf.money(amount);
    }
}
