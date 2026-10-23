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

    RebuildStatusView startRebuild(String requestedBy);

    RebuildStatusView rebuildStatus();
}
