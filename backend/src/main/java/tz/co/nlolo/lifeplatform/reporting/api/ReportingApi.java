package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/** Every call is scoped to the caller's tenant (TenantContext). */
public interface ReportingApi {
    List<ReportDefinitionView> catalogue();

    /** @throws ReportNotFoundException for an unknown key; @throws ReportParameterException for a filter the report cannot take */
    ReportResult run(String key, ReportFilter filter);

    RebuildStatusView startRebuild(String requestedBy);

    RebuildStatusView rebuildStatus();
}
