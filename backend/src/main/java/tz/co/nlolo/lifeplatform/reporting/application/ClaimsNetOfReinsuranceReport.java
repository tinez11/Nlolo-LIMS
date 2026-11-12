package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reinsurance.api.ClaimRecoveryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.ClaimFact;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.DATE;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Claims gross and net of reinsurance (2026-11-09, the reports redesign C7, built with the reinsurance batch): every
 * claim approved in the months, what the treaties make recoverable on it, and what the company keeps. The recoverable
 * is reinsurance's own recovery record for the claim; it is settled through the quarterly statement, which the
 * reinsurance report follows.
 */
@Component
public class ClaimsNetOfReinsuranceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("claims-net-of-reinsurance",
        "Claims net of reinsurance",
        "Every claim approved in the period, what the reinsurance treaties make recoverable on it, and what the company"
            + " keeps.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods().from("REPORTING_COPY", "REINSURANCE");

    private final ReportQueries queries;
    private final ReinsuranceApi reinsurance;

    public ClaimsNetOfReinsuranceReport(ReportQueries queries, ReinsuranceApi reinsurance) {
        this.queries = queries;
        this.reinsurance = reinsurance;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return body(tenantId, f, labels).sections();
    }

    private record Totals(BigDecimal gross, BigDecimal recoverable) {
        BigDecimal net() {
            return gross.subtract(recoverable);
        }

        BigDecimal share() {
            return gross.signum() == 0 ? null
                : recoverable.multiply(BigDecimal.valueOf(100)).divide(gross, 1, RoundingMode.HALF_UP);
        }
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        ClaimsReporting.Span now = ClaimsReporting.current(f);
        ClaimsReporting.Span before = ClaimsReporting.prior(f);
        List<ClaimFact> claims = queries.claimFacts(tenantId, now.to(), f);

        List<ReportColumn> columns = List.of(Sections.link("policy", "Policy", "POLICY"), col("type", "Claim type", TEXT),
            col("approvedOn", "Approved on", DATE), col("gross", "Approved (gross)", MONEY),
            col("recoverable", "Recoverable", MONEY), col("net", "Kept (net)", MONEY), col("share", "Recovered %", PERCENT));
        List<Map<String, String>> rows = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal recoverable = BigDecimal.ZERO;
        for (ClaimFact c : claims) {
            if (!now.holds(c.approvedOn()) || c.approvedAmount() == null) continue;
            BigDecimal recovered = recoverable(c);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policy", c.policyNumber());
            row.put("type", ClaimsReporting.typeName(c.claimType()));
            row.put("approvedOn", c.approvedOn().toString());
            row.put("gross", Figures.money(c.approvedAmount()));
            row.put("recoverable", Figures.money(recovered));
            row.put("net", Figures.money(c.approvedAmount().subtract(recovered)));
            row.put("share", Figures.percent(recovered, c.approvedAmount()));
            rows.add(row);
            gross = gross.add(c.approvedAmount());
            recoverable = recoverable.add(recovered);
        }
        Totals t = new Totals(gross, recoverable);
        Map<String, String> totals = rows.isEmpty() ? Map.of() : totalRow(t);

        Totals was = null;
        if (before != null) {
            BigDecimal g = BigDecimal.ZERO;
            BigDecimal r = BigDecimal.ZERO;
            for (ClaimFact c : claims) {
                if (!before.holds(c.approvedOn()) || c.approvedAmount() == null) continue;
                g = g.add(c.approvedAmount());
                r = r.add(recoverable(c));
            }
            was = new Totals(g, r);
        }

        List<ReportFigure> figures = new ArrayList<>(List.of(
            ClaimsReporting.money("gross", "Claims approved (gross)", t.gross(), was == null ? null : was.gross(), "DOWN", true),
            ClaimsReporting.money("recoverable", "Recoverable from reinsurers", t.recoverable(),
                was == null ? null : was.recoverable(), null, true),
            ClaimsReporting.money("net", "Kept (net of reinsurance)", t.net(), was == null ? null : was.net(), "DOWN", true)));
        if (t.share() != null) {
            figures.add(ClaimsReporting.ratio("share", "Share recovered", t.share(), was == null ? null : was.share(), "UP",
                "PERCENT", false));
        }
        return new ReportBody(List.of(new ReportSection("Claims approved, " + now.label(), columns, rows, totals)),
            figures, null, before == null ? null : "compared with " + before.label());
    }

    /** What the treaties make recoverable on one claim: the sum of its recovery records. */
    private BigDecimal recoverable(ClaimFact c) {
        return reinsurance.listRecoveriesForClaim(c.claimId()).stream().map(ClaimRecoveryView::recoverableAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, String> totalRow(Totals t) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("policy", "Total");
        row.put("type", "");
        row.put("approvedOn", "");
        row.put("gross", Figures.money(t.gross()));
        row.put("recoverable", Figures.money(t.recoverable()));
        row.put("net", Figures.money(t.net()));
        row.put("share", Figures.percent(t.recoverable(), t.gross()));
        return row;
    }
}
