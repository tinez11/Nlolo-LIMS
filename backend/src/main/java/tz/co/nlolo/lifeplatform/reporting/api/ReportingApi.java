package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Every call is scoped to the caller's tenant (TenantContext). */
public interface ReportingApi {
    List<ReportDefinitionView> catalogue();

    /** @throws ReportNotFoundException for an unknown key; @throws ReportParameterException for a filter the report cannot take */
    ReportResult run(String key, ReportFilter filter);

    /** What one line of a report is made of -- the computation tree (2026-11-09). 404 for a line it does not have. */
    ReportParts parts(String key, String lineId, ReportFilter filter, int page);

    /**
     * Premiums collected per month, {@code from} through {@code to} inclusive, oldest first, one entry per month and
     * currency. A month in which nothing was collected is ABSENT rather than zero: the caller decides whether a gap is
     * drawn as nothing, and a zero would be a claim the projection did not make.
     */
    List<MonthlyAmount> monthlyPremiumCollections(YearMonth from, YearMonth to);

    /**
     * The same collections over {@code from} through {@code to}, totalled per product category and currency. The
     * categories of one currency add up to that currency's total for the same months.
     */
    List<CategoryAmount> premiumCollectionsByCategory(YearMonth from, YearMonth to);

    /**
     * Policies issued per month, {@code from} through {@code to}. A month with none is absent. {@code agentIds} null
     * means every policy in the tenant; an EMPTY set means none -- an agent with no team sees nothing, never everything.
     */
    List<MonthlyCount> policiesIssuedByMonth(YearMonth from, YearMonth to, Set<UUID> agentIds);

    /** Policies in force on {@code asOf}, per product category, scoped as {@link #policiesIssuedByMonth} is. */
    List<CategoryCount> policiesInForceByCategory(LocalDate asOf, Set<UUID> agentIds);

    /** Every version of the solvency factors, newest first, ending with the built-in placeholder set (2026-11-09). */
    List<SolvencyFactorSetView> solvencyFactorSets();

    /** Publishes a new version. @throws ReportParameterException for factors it cannot use, in words. */
    SolvencyFactorSetView publishSolvencyFactorSet(NewSolvencyFactorSet set, String publishedBy);

    RebuildStatusView startRebuild(String requestedBy);

    RebuildStatusView rebuildStatus();

    /**
     * What the premiums-to-follow-up list is asked for (2026-11-11).
     *
     * @param view     DUE_TODAY, DUE_SOON (due in the next {@code soonDays}) or OVERDUE
     * @param soonDays 7, 14 or 30
     * @param lateBand one of {@link #LATE_BANDS}, OVERDUE only; null for every band
     */
    record FollowUpQuery(String view, int soonDays, String lateBand, UUID agentId, String branchCode, UUID productId,
                         String search, int page, int pageSize) {}

    List<String> FOLLOW_UP_VIEWS = List.of("DUE_TODAY", "DUE_SOON", "OVERDUE");
    List<Integer> SOON_DAYS = List.of(7, 14, 30);
    List<String> LATE_BANDS = List.of("1–7 days", "8–14 days", "15–30 days", "31–60 days", "61–90 days",
        "More than 90 days");

    /** Premiums to follow up across the company (staff). */
    FollowUpPage followUp(FollowUpQuery query);

    /** Premiums to follow up on the policies this agent's team sold -- the agent's own and their downline's. */
    FollowUpPage followUpForAgentParty(UUID agentPartyId, FollowUpQuery query);

    /** The same list, every row, as a report for Excel or PDF. {@code agentPartyId} null for staff. */
    ReportResult followUpReport(FollowUpQuery query, UUID agentPartyId);

    /** The collection figures for premiums due {@code from} to {@code to}, and what is due or overdue on {@code today}. */
    CollectionFigures collectionFigures(LocalDate from, LocalDate to, LocalDate today);

    /** Records that a list was downloaded, by whom (reporting.ListDownloaded, kept by audit). */
    void listDownloaded(String list, String format, int rows, String by);
}
