package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.api.CategoryAmount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParts;
import tz.co.nlolo.lifeplatform.reporting.api.CategoryCount;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyCount;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;
import tz.co.nlolo.lifeplatform.reporting.api.NewSolvencyFactorSet;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;

import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Not @Transactional on purpose: a report is several independent reads, and a label lookup that fails in
 * another module must not abort the transaction the report's own queries run in. */
@Service
public class ReportingApiImpl implements ReportingApi {

    /** Catalogue order: the order a manager reads the business in, then the order a ledger is read in. */
    static final List<String> ORDER = List.of("policies-in-force", "products", "new-business", "lapse-rate",
        "persistency", "free-look-and-not-taken-up", "premium-collections", "clients",
        "trial-balance", "general-ledger", "balance-sheet", "income-statement",
        // Claims (2026-11-09): what was incurred, the ratio to premium, what became of claims, the money, the time.
        "claims-incurred", "loss-ratio", "claim-outcomes", "claims-approved-vs-paid", "claims-turnaround",
        "early-claims", "claims-outstanding", "claims-net-of-reinsurance",
        // Solvency and risk (2026-11-09): the ratio, the ratio under shocks, the ratio ahead.
        "capital-adequacy", "stress-tests", "solvency-forecast",
        // Reinsurance and commissions (2026-11-09, batch 5).
        "reinsurance", "commissions");

    private final Map<String, Report> reports = new LinkedHashMap<>();
    private final LabelsFactory labelsFactory;
    private final RebuildService rebuildService;
    private final Clock clock;
    private final ReportQueries queries;
    private final SolvencyFactorSets solvencyFactorSets;
    /** Premiums to follow up and the collection figures (2026-11-11). */
    private final FollowUpQueue followUpQueue;

    public ReportingApiImpl(List<Report> beans, LabelsFactory labelsFactory, RebuildService rebuildService,
                            ReportingClock clock, ReportQueries queries, SolvencyFactorSets solvencyFactorSets,
                            FollowUpQueue followUpQueue) {
        this.followUpQueue = followUpQueue;
        beans.stream().sorted(Comparator.comparingInt(r -> {
            int i = ORDER.indexOf(r.definition().key());
            return i < 0 ? Integer.MAX_VALUE : i;
        })).forEach(r -> reports.put(r.definition().key(), r));
        this.labelsFactory = labelsFactory;
        this.rebuildService = rebuildService;
        this.clock = clock.clock();
        this.queries = queries;
        this.solvencyFactorSets = solvencyFactorSets;
    }

    @Override
    public List<MonthlyAmount> monthlyPremiumCollections(YearMonth from, YearMonth to) {
        return queries.monthlyCollections(TenantContext.get(), from.atDay(1), to.atEndOfMonth());
    }

    @Override
    public List<CategoryAmount> premiumCollectionsByCategory(YearMonth from, YearMonth to) {
        return queries.collectionsByCategory(TenantContext.get(), from.atDay(1), to.atEndOfMonth());
    }

    @Override
    public List<MonthlyCount> policiesIssuedByMonth(YearMonth from, YearMonth to, Set<UUID> agentIds) {
        return queries.issuedByMonth(TenantContext.get(), from.atDay(1), to.atEndOfMonth(), agentIds);
    }

    @Override
    public List<CategoryCount> policiesInForceByCategory(LocalDate asOf, Set<UUID> agentIds) {
        return queries.inForceByCategory(TenantContext.get(), asOf, agentIds);
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
        ReportBody body = report.body(TenantContext.get(), filter, labels);
        return new ReportResult(key, report.definition().title(), FilterSummary.describe(report.definition(), filter, labels),
            Instant.now(clock), body.sections(), body.figures(), body.chart(), body.comparisonLabel(), body.notices());
    }

    @Override
    public ReportParts parts(String key, String lineId, ReportFilter filter, int page) {
        Report report = reports.get(key);
        if (report == null) throw new ReportNotFoundException(key);
        ReportFilters.validate(report.definition(), filter);
        return report.parts(TenantContext.get(), lineId, filter, page, labelsFactory.create());
    }

    @Override
    public List<SolvencyFactorSetView> solvencyFactorSets() {
        return solvencyFactorSets.all(TenantContext.get());
    }

    @Override
    public SolvencyFactorSetView publishSolvencyFactorSet(NewSolvencyFactorSet set, String publishedBy) {
        return solvencyFactorSets.publish(TenantContext.get(), set, publishedBy);
    }

    @Override
    public RebuildStatusView startRebuild(String requestedBy) {
        return rebuildService.start(TenantContext.get(), requestedBy);
    }

    @Override
    public RebuildStatusView rebuildStatus() {
        return rebuildService.status(TenantContext.get());
    }

    @Override
    public tz.co.nlolo.lifeplatform.reporting.api.FollowUpPage followUp(FollowUpQuery query) {
        return followUpQueue.page(query, null);
    }

    @Override
    public tz.co.nlolo.lifeplatform.reporting.api.FollowUpPage followUpForAgentParty(UUID agentPartyId, FollowUpQuery query) {
        if (agentPartyId == null) throw new IllegalArgumentException("An agent's list needs the agent's party");
        return followUpQueue.page(query, agentPartyId);
    }

    @Override
    public ReportResult followUpReport(FollowUpQuery query, UUID agentPartyId) {
        return followUpQueue.report(query, agentPartyId);
    }

    @Override
    public tz.co.nlolo.lifeplatform.reporting.api.CollectionFigures collectionFigures(LocalDate from, LocalDate to,
                                                                                     LocalDate today) {
        return followUpQueue.figures(from, to, today);
    }

    @Override
    public void listDownloaded(String list, String format, int rows, String by) {
        followUpQueue.downloaded(list, format, rows, by);
    }
}
