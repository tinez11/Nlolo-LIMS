package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.util.List;
import java.util.UUID;

/** One report. The filter has already been validated against definition() when run is called. */
public interface Report {
    ReportDefinitionView definition();

    List<ReportSection> run(UUID tenantId, ReportFilter filter, Labels labels);
}
