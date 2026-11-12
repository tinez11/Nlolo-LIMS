package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceDueView;
import tz.co.nlolo.lifeplatform.billing.api.OpenPremiumView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.reporting.api.CollectionFigures;
import tz.co.nlolo.lifeplatform.reporting.api.FollowUpPage;
import tz.co.nlolo.lifeplatform.reporting.api.FollowUpRow;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi.FollowUpQuery;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.FollowUpPolicyRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.DATE;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.api.ReportingApi.FOLLOW_UP_VIEWS;
import static tz.co.nlolo.lifeplatform.reporting.api.ReportingApi.LATE_BANDS;
import static tz.co.nlolo.lifeplatform.reporting.api.ReportingApi.SOON_DAYS;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Premiums to follow up (2026-11-11, analytics plan 1): billing's premiums still owed, each with the client to call, the
 * product, the agent and branch from the reporting projection, and what was already done.
 *
 * <p>Composed here because reporting may read billing, party and distribution; billing may read none of them.
 *
 * <p>Filtered, sorted and paged in memory: the list is the company's premiums owed today or within 30 days -- hundreds,
 * not millions. Should it grow past a few thousand, page in billing's query instead.
 */
@Service
public class FollowUpQueue {

    static final String TITLE = "Premiums to follow up";
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final int MAX_PAGE_SIZE = 200;

    private final BillingApi billing;
    private final PartyApi parties;
    private final DistributionApi distribution;
    private final ReportQueries queries;
    private final LabelsFactory labelsFactory;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FollowUpQueue(BillingApi billing, PartyApi parties, DistributionApi distribution, ReportQueries queries,
                         LabelsFactory labelsFactory, ApplicationEventPublisher events, ReportingClock clock) {
        this.billing = billing;
        this.parties = parties;
        this.distribution = distribution;
        this.queries = queries;
        this.labelsFactory = labelsFactory;
        this.events = events;
        this.clock = clock.clock();
    }

    public FollowUpPage page(FollowUpQuery q, UUID agentPartyId) {
        validate(q);
        LocalDate today = CivilDates.today(clock);
        List<FollowUpRow> scoped = scoped(q, agentPartyId, today);
        List<FollowUpRow> shown = sorted(q.view(), scoped.stream().filter(r -> inView(r, q, today)).toList());

        int size = Math.min(Math.max(q.pageSize(), 1), MAX_PAGE_SIZE);
        int page = Math.max(q.page(), 0);
        List<FollowUpRow> slice = shown.stream().skip((long) page * size).limit(size).toList();
        List<FollowUpRow> overdue = scoped.stream().filter(r -> r.dueDate().isBefore(today)).toList();
        return new FollowUpPage(slice, shown.size(), page, size,
            scoped.stream().filter(r -> r.dueDate().isEqual(today)).count(),
            scoped.stream().filter(r -> r.dueDate().isAfter(today)
                && !r.dueDate().isAfter(today.plusDays(q.soonDays()))).count(),
            overdue.size(),
            overdue.stream().map(FollowUpRow::owed).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Every row of the list as asked, as one report table -- what Excel and PDF print. */
    public ReportResult report(FollowUpQuery q, UUID agentPartyId) {
        validate(q);
        LocalDate today = CivilDates.today(clock);
        List<FollowUpRow> rows = sorted(q.view(),
            scoped(q, agentPartyId, today).stream().filter(r -> inView(r, q, today)).toList());
        List<ReportColumn> columns = List.of(col("client", "Client", TEXT), col("phone", "Phone", TEXT),
            col("policy", "Policy", TEXT), col("product", "Product", TEXT), col("agent", "Agent", TEXT),
            col("owed", "Owed", MONEY), col("dueDate", "Due date", DATE), col("daysLate", "Days late", COUNT),
            col("done", "What was done", TEXT));
        List<Map<String, String>> table = new ArrayList<>();
        for (FollowUpRow r : rows) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("client", Objects.requireNonNullElse(r.clientName(), ""));
            row.put("phone", Objects.requireNonNullElse(r.clientPhone(), ""));
            row.put("policy", r.policyNumber());
            row.put("product", Objects.requireNonNullElse(r.productName(), ""));
            row.put("agent", Objects.requireNonNullElse(r.agentName(), ""));
            row.put("owed", Figures.money(r.owed()));
            row.put("dueDate", r.dueDate().toString());
            row.put("daysLate", Figures.count(r.daysLate()));
            row.put("done", r.actions().isEmpty() ? "" : inWords(r.actions().get(0)));
            table.add(row);
        }
        // Only the money adds up: a total of days late would be a number that means nothing.
        Map<String, String> totals = new LinkedHashMap<>();
        if (!table.isEmpty()) {
            columns.forEach(c -> totals.put(c.key(), ""));
            totals.put("client", "Total (" + table.size() + ")");
            totals.put("owed", Figures.money(rows.stream().map(FollowUpRow::owed).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        return new ReportResult("premiums-to-follow-up", TITLE, describe(q),
            Instant.now(clock), List.of(new ReportSection(TITLE, columns, table, totals)));
    }

    /**
     * Of the premiums due {@code from} to {@code to}: how much was asked, how much is paid, the rate; and, on
     * {@code today}, what is due today and what is overdue. In TZS, the currency every premium is billed in today --
     * the same scope billing's own owed-by-policy figure keeps.
     *
     * <p>Explained in CollectionsFigureDefinitions -- change the words with the formula.
     */
    public CollectionFigures figures(LocalDate from, LocalDate to, LocalDate today) {
        return figures(from, to, today, null);
    }

    /**
     * The same figures for one agent's customers (2026-11-12, analytics plan 3, D4): the premiums on policies their team
     * sold -- the scope the agent's follow-up list keeps. {@code agentPartyId} null for the whole company.
     */
    public CollectionFigures figures(LocalDate from, LocalDate to, LocalDate today, UUID agentPartyId) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ReportParameterException("from and to are required, and to cannot be before from");
        }
        List<InvoiceDueView> fellDue = billing.invoicesDueBetween(from, to);
        if (agentPartyId != null) {
            Set<UUID> team = new HashSet<>(distribution.resolveAgentTeam(agentPartyId));
            Set<String> teamPolicies = queries.followUpPolicies(TenantContext.get(), today,
                    fellDue.stream().map(InvoiceDueView::policyNumber).collect(Collectors.toSet())).stream()
                .filter(p -> p.agentId() != null && team.contains(p.agentId()))
                .map(FollowUpPolicyRow::policyNumber).collect(Collectors.toSet());
            fellDue = fellDue.stream().filter(i -> teamPolicies.contains(i.policyNumber())).toList();
        }
        BigDecimal due = BigDecimal.ZERO;
        BigDecimal owed = BigDecimal.ZERO;
        for (InvoiceDueView i : fellDue) {
            if (!"TZS".equals(i.currency())) continue;
            due = due.add(i.amount());
            owed = owed.add(i.owed());
        }
        BigDecimal paid = due.subtract(owed);
        String rate = due.signum() == 0 ? null
            : paid.multiply(BigDecimal.valueOf(100)).divide(due, 1, RoundingMode.HALF_UP).toPlainString();

        // The same rows the follow-up list holds, so the dashboard's "overdue" and the list's Overdue tab agree.
        long todayCount = 0;
        long overdueCount = 0;
        BigDecimal todayOwed = BigDecimal.ZERO;
        BigDecimal overdueOwed = BigDecimal.ZERO;
        for (FollowUpRow p : scoped(new FollowUpQuery("OVERDUE", SOON_DAYS.get(0), null, null, null, null, null, 0, 0),
                agentPartyId, today)) {
            if (!"TZS".equals(p.currency())) continue;
            if (p.dueDate().isEqual(today)) {
                todayCount++;
                todayOwed = todayOwed.add(p.owed());
            } else if (p.dueDate().isBefore(today)) {
                overdueCount++;
                overdueOwed = overdueOwed.add(p.owed());
            }
        }
        return new CollectionFigures(from, to, due, paid, rate, todayCount, todayOwed, overdueCount, overdueOwed, "TZS");
    }

    /**
     * A list left the platform as a file (2026-11-11): who, which list, how many rows. Published in a transaction so
     * audit's AFTER_COMMIT listener keeps it -- outside one the event would be dropped unrecorded.
     */
    @Transactional
    public void downloaded(String list, String format, int rows, String by) {
        events.publishEvent(DomainEventEnvelope.of("reporting.ListDownloaded", TenantContext.get(),
            Map.of("list", list, "format", format == null ? "" : format, "rows", rows, "by", by == null ? "" : by)));
    }

    // ---- composing the rows -------------------------------------------------------------------------------------

    private List<FollowUpRow> scoped(FollowUpQuery q, UUID agentPartyId, LocalDate today) {
        int soon = q.soonDays();
        List<OpenPremiumView> open = billing.openPremiums(today.plusDays(soon));
        if (open.isEmpty()) return List.of();

        Map<String, FollowUpPolicyRow> policies = queries.followUpPolicies(TenantContext.get(), today,
                open.stream().map(OpenPremiumView::policyNumber).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(FollowUpPolicyRow::policyNumber, Function.identity()));

        // An agent sees the policies their team sold -- an EMPTY team sees nothing, never everything.
        Set<UUID> team = agentPartyId == null ? null : new HashSet<>(distribution.resolveAgentTeam(agentPartyId));

        List<OpenPremiumView> kept = open.stream().filter(p -> {
            FollowUpPolicyRow policy = policies.get(p.policyNumber());
            if (policy == null) return false;
            if (team != null && (policy.agentId() == null || !team.contains(policy.agentId()))) return false;
            if (q.agentId() != null && !q.agentId().equals(policy.agentId())) return false;
            if (q.branchCode() != null && !q.branchCode().equals(policy.branchCode())) return false;
            return q.productId() == null || q.productId().equals(policy.productId());
        }).toList();
        if (kept.isEmpty()) return List.of();

        Set<UUID> holders = kept.stream().map(p -> policies.get(p.policyNumber()).holderPartyId())
            .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> names = parties.displayNames(holders);
        Map<UUID, String> phones = parties.phoneNumbers(holders);
        Labels labels = labelsFactory.create();

        String search = q.search() == null || q.search().isBlank() ? null : q.search().trim().toLowerCase(Locale.ROOT);
        List<FollowUpRow> rows = new ArrayList<>();
        for (OpenPremiumView p : kept) {
            FollowUpPolicyRow policy = policies.get(p.policyNumber());
            String client = names.get(policy.holderPartyId());
            String phone = phones.get(policy.holderPartyId());
            if (search != null && !matches(search, p.policyNumber(), client, phone)) continue;
            long daysLate = Math.max(0, ChronoUnit.DAYS.between(p.dueDate(), today));
            rows.add(new FollowUpRow(p.invoiceId(), p.policyNumber(), client, phone, labels.product(policy.productId()),
                policy.agentId(), policy.agentId() == null ? null : labels.agent(policy.agentId()), policy.branchCode(),
                p.owed(), p.currency(), p.dueDate(), daysLate, lateBand(daysLate), Labels.words(policy.status()),
                p.lapseExpectedOn(), p.agentCashHeld(),
                p.agentCashAgentId() == null ? null : labels.agent(p.agentCashAgentId()), p.actions()));
        }
        return rows;
    }

    private static boolean inView(FollowUpRow r, FollowUpQuery q, LocalDate today) {
        return switch (q.view()) {
            case "DUE_TODAY" -> r.dueDate().isEqual(today);
            case "DUE_SOON" -> r.dueDate().isAfter(today) && !r.dueDate().isAfter(today.plusDays(q.soonDays()));
            default -> r.dueDate().isBefore(today) && (q.lateBand() == null || q.lateBand().equals(r.lateBand()));
        };
    }

    private static List<FollowUpRow> sorted(String view, List<FollowUpRow> rows) {
        Comparator<FollowUpRow> order = "OVERDUE".equals(view)
            ? Comparator.comparingLong(FollowUpRow::daysLate).reversed().thenComparing(FollowUpRow::policyNumber)
            : Comparator.comparing(FollowUpRow::dueDate).thenComparing(FollowUpRow::policyNumber);
        return rows.stream().sorted(order).toList();
    }

    private static boolean matches(String search, String... values) {
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(search)) return true;
        }
        return false;
    }

    static String lateBand(long daysLate) {
        if (daysLate <= 0) return null;
        if (daysLate <= 7) return LATE_BANDS.get(0);
        if (daysLate <= 14) return LATE_BANDS.get(1);
        if (daysLate <= 30) return LATE_BANDS.get(2);
        if (daysLate <= 60) return LATE_BANDS.get(3);
        if (daysLate <= 90) return LATE_BANDS.get(4);
        return LATE_BANDS.get(5);
    }

    /** The newest thing done, in words: "Called 8 Nov — promised 12 Nov", "Reminder sent 3 Nov". */
    static String inWords(OpenPremiumView.ActionView a) {
        String on = SHORT.format(CivilDates.of(a.recordedAt()));
        return switch (a.kind()) {
            case "CALL" -> "Called " + on + " — " + (a.promisedOn() != null
                ? "promised " + SHORT.format(a.promisedOn())
                : Labels.words(a.outcome()).toLowerCase(Locale.ROOT));
            case "REMINDER" -> "Reminder sent " + on;
            case "PAYMENT_REQUEST" -> "Payment request sent " + on;
            default -> Labels.words(a.kind()) + " " + on;
        };
    }

    private static String describe(FollowUpQuery q) {
        return switch (q.view()) {
            case "DUE_TODAY" -> "Due today";
            case "DUE_SOON" -> "Due in the next " + q.soonDays() + " days";
            default -> q.lateBand() == null ? "Overdue" : "Overdue " + q.lateBand();
        };
    }

    private static void validate(FollowUpQuery q) {
        if (q.view() == null || !FOLLOW_UP_VIEWS.contains(q.view())) {
            throw new ReportParameterException("view must be one of " + FOLLOW_UP_VIEWS + "; got '" + q.view() + "'");
        }
        if (!SOON_DAYS.contains(q.soonDays())) {
            throw new ReportParameterException("soonDays must be one of " + SOON_DAYS + "; got " + q.soonDays());
        }
        if (q.lateBand() != null && !LATE_BANDS.contains(q.lateBand())) {
            throw new ReportParameterException("lateBand must be one of " + LATE_BANDS + "; got '" + q.lateBand() + "'");
        }
    }
}
