package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;
import java.util.Set;

/**
 * One catalogue entry: everything a caller needs to ask for this report correctly, before asking.
 *
 * @param comparable      the report accepts a second as-of date to compare against
 * @param requiresAccount the report is about ONE chart account and cannot run without one. The console asks
 *                        for it beside the other filters rather than letting the server refuse a submission:
 *                        a field nobody filled in is not a failure to report, it is a question to ask.
 * @param sources         what the report reads (2026-11-11, analytics plan 2): REPORTING_COPY (the reports' own copy,
 *                        updated as things happen), LEDGER, BILLING, DISTRIBUTION, REINSURANCE -- so a page can say
 *                        how fresh its figures are, and "What the figures mean" where they come from
 * @param figures         the report's figures explained (decision K1); empty until written (Plan 2b)
 * @param companyWide     the report is about the whole company and cannot be cut by branch (2026-11-23, branch
 *                        views): the ledger, solvency and reinsurance reports, whose postings and treaties carry no
 *                        branch or only some of it. A branch given to one is refused, never silently ignored.
 */
public record ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                   DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable,
                                   boolean requiresAccount, boolean comparesPeriods, List<String> sources,
                                   List<FigureDefinition> figures, boolean companyWide) {

    public static final Set<String> SOURCES = Set.of("REPORTING_COPY", "LEDGER", "BILLING", "DISTRIBUTION", "REINSURANCE");

    public ReportDefinitionView {
        sources = sources == null ? List.of() : List.copyOf(sources);
        figures = figures == null ? List.of() : List.copyOf(figures);
        for (String s : sources) {
            if (!SOURCES.contains(s)) throw new IllegalArgumentException("Unknown source " + s + "; one of " + SOURCES);
        }
    }

    /** Every report before branch views (2026-11-23): one that can be cut by branch. */
    public ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable,
                                boolean requiresAccount, boolean comparesPeriods, List<String> sources,
                                List<FigureDefinition> figures) {
        this(key, title, description, category, dateFilterKind, groupByOptions, comparable, requiresAccount,
            comparesPeriods, sources, figures, false);
    }

    public ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable,
                                boolean requiresAccount, boolean comparesPeriods) {
        this(key, title, description, category, dateFilterKind, groupByOptions, comparable, requiresAccount,
            comparesPeriods, List.of(), List.of());
    }

    /** Every report before the redesign, which compares with no other period (2026-11-09). */
    public ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable,
                                boolean requiresAccount) {
        this(key, title, description, category, dateFilterKind, groupByOptions, comparable, requiresAccount, false);
    }

    /** A report that takes any account, or none -- every report but the general ledger. */
    public ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable) {
        this(key, title, description, category, dateFilterKind, groupByOptions, comparable, false);
    }

    /** The same report, offering "compare with" the prior period or the same period last year. */
    public ReportDefinitionView comparingPeriods() {
        return new ReportDefinitionView(key, title, description, category, dateFilterKind, groupByOptions, comparable,
            requiresAccount, true, sources, figures, companyWide);
    }

    /** The same report, reading these sources (2026-11-11). */
    public ReportDefinitionView from(String... readFrom) {
        return new ReportDefinitionView(key, title, description, category, dateFilterKind, groupByOptions, comparable,
            requiresAccount, comparesPeriods, List.of(readFrom), figures, companyWide);
    }

    /** The same report, its figures explained (decision K1). */
    public ReportDefinitionView explaining(FigureDefinition... explained) {
        return new ReportDefinitionView(key, title, description, category, dateFilterKind, groupByOptions, comparable,
            requiresAccount, comparesPeriods, sources, List.of(explained), companyWide);
    }

    /** The same report, about the whole company: it cannot be cut by branch (2026-11-23). */
    public ReportDefinitionView aboutTheWholeCompany() {
        return new ReportDefinitionView(key, title, description, category, dateFilterKind, groupByOptions, comparable,
            requiresAccount, comparesPeriods, sources, figures, true);
    }
}
