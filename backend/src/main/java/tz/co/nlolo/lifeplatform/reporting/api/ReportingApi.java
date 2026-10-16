package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.YearMonth;
import java.util.List;

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

    RebuildStatusView startRebuild(String requestedBy);

    RebuildStatusView rebuildStatus();
}
