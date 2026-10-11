package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Not @Transactional on purpose: a report is several independent reads, and a label lookup that fails in
 * another module must not abort the transaction the report's own queries run in. */
@Service
public class ReportingApiImpl implements ReportingApi {

    /** Catalogue order: the order a manager reads the business in. */
    static final List<String> ORDER = List.of("policies-in-force", "new-business", "lapse-rate", "persistency",
        "premium-collections", "clients");

    private final Map<String, Report> reports = new LinkedHashMap<>();
    private final LabelsFactory labelsFactory;
    private final RebuildService rebuildService;
    private final Clock clock;

    public ReportingApiImpl(List<Report> beans, LabelsFactory labelsFactory, RebuildService rebuildService,
                            ReportingClock clock) {
        beans.stream().sorted(Comparator.comparingInt(r -> {
            int i = ORDER.indexOf(r.definition().key());
            return i < 0 ? Integer.MAX_VALUE : i;
        })).forEach(r -> reports.put(r.definition().key(), r));
        this.labelsFactory = labelsFactory;
        this.rebuildService = rebuildService;
        this.clock = clock.clock();
    }

    @Override
    public List<ReportDefinitionView> catalogue() {
        return reports.values().stream().map(Report::definition).toList();
    }

    @Override
    public ReportResult run(String key, ReportFilter filter) {
        Report report = reports.get(key);
        if (report == null) throw new ReportNotFoundException(key);
        ReportFilters.validate(report.definition(), filter);
        Labels labels = labelsFactory.create();
        List<ReportSection> sections = report.run(TenantContext.get(), filter, labels);
        return new ReportResult(key, report.definition().title(), FilterSummary.describe(report.definition(), filter, labels),
            Instant.now(clock), sections);
    }

    @Override
    public RebuildStatusView startRebuild(String requestedBy) {
        return rebuildService.start(TenantContext.get(), requestedBy);
    }

    @Override
    public RebuildStatusView rebuildStatus() {
        return rebuildService.status(TenantContext.get());
    }
}
