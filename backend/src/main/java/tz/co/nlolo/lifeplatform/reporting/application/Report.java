package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParts;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.util.List;
import java.util.UUID;

/**
 * One report. The filter has already been validated against definition() when run is called.
 *
 * <p>The defaults below are plain Java with no proxy in the way: no report is {@code @Transactional}, so the trap of a
 * default method skipping a transaction proxy does not apply here.
 */
public interface Report {
    ReportDefinitionView definition();

    List<ReportSection> run(UUID tenantId, ReportFilter filter, Labels labels);

    /** Sections plus headline figures, a chart and a comparison (2026-11-09). A report without them answers sections. */
    default ReportBody body(UUID tenantId, ReportFilter filter, Labels labels) {
        return ReportBody.of(run(tenantId, filter, labels));
    }

    /** What one of this report's lines is made of: the computation tree (2026-11-09). Only some reports have one. */
    default ReportParts parts(UUID tenantId, String lineId, ReportFilter filter, int page, Labels labels) {
        throw new ReportNotFoundException(definition().key() + " line " + lineId);
    }
}
