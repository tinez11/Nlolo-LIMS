package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.AgeBands;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.IndividualRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Who the clients are. Individuals, corporates and groups are counted separately; a scheme's members are its
 * lives, not clients of their own. Members are counts over time (report_lives_change) because the member
 * events carry no member id.
 */
@Component
public class ClientsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("clients", "Clients",
        "Clients by type and KYC status, individuals by age band and sex, and every group or corporate scheme with "
            + "its lives covered, joiners and leavers.",
        ReportCategory.MANAGEMENT, DateFilterKind.AS_OF_AND_PERIOD, List.of(), false);

    private final ReportQueries queries;

    public ClientsReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        List<ReportSection> sections = new ArrayList<>();

        List<ReportColumn> summary = List.of(col("partyType", "Client type", TEXT), col("total", "Clients", COUNT),
            col("newInPeriod", "New in period", COUNT), col("kycVerified", "KYC verified", COUNT),
            col("kycPending", "KYC pending", COUNT), col("kycRejected", "KYC rejected", COUNT),
            col("withoutPolicyInForce", "No policy in force", COUNT));
        List<Map<String, String>> summaryRows = queries.clientSummary(tenantId, f.asOf(), f.from(), f.to(), f.clientType())
            .stream().map(r -> {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("partyType", r.partyType());
                row.put("total", Figures.count(r.total()));
                row.put("newInPeriod", Figures.count(r.newInPeriod()));
                row.put("kycVerified", Figures.count(r.verified()));
                row.put("kycPending", Figures.count(r.pending()));
                row.put("kycRejected", Figures.count(r.rejected()));
                row.put("withoutPolicyInForce", Figures.count(r.withoutInForce()));
                return row;
            }).toList();
        sections.add(new ReportSection("Clients on " + f.asOf(), summary, summaryRows, Sections.totals(summary, summaryRows)));

        if (f.clientType() == null || "INDIVIDUAL".equals(f.clientType())) {
            List<IndividualRow> people = queries.individuals(tenantId, f.asOf());
            sections.add(ageAndSex(people, f));
            sections.add(policiesPerClient(people));
        }
        sections.add(schemes(tenantId, f));
        return sections;
    }

    private static ReportSection ageAndSex(List<IndividualRow> people, ReportFilter f) {
        Map<String, long[]> bands = new LinkedHashMap<>();
        AgeBands.ORDER.forEach(b -> bands.put(b, new long[3]));
        for (IndividualRow p : people) {
            long[] counts = bands.get(AgeBands.of(p.dateOfBirth(), f.asOf()));
            counts["FEMALE".equals(p.sex()) ? 0 : "MALE".equals(p.sex()) ? 1 : 2]++;
        }
        List<ReportColumn> columns = List.of(col("ageBand", "Age band", TEXT), col("female", "Female", COUNT),
            col("male", "Male", COUNT), col("unknown", "Not recorded", COUNT), col("total", "Total", COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        bands.forEach((band, c) -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("ageBand", band);
            row.put("female", Figures.count(c[0]));
            row.put("male", Figures.count(c[1]));
            row.put("unknown", Figures.count(c[2]));
            row.put("total", Figures.count(c[0] + c[1] + c[2]));
            rows.add(row);
        });
        return new ReportSection("Individuals by age band and sex, age on " + f.asOf(), columns, rows,
            Sections.totals(columns, rows));
    }

    private static ReportSection policiesPerClient(List<IndividualRow> people) {
        long[] buckets = new long[4];
        people.forEach(p -> buckets[(int) Math.min(p.inForcePolicies(), 3)]++);
        List<ReportColumn> columns = List.of(col("policiesInForce", "Policies in force", TEXT), col("clients", "Clients", COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        String[] names = { "0", "1", "2", "3 or more" };
        for (int i = 0; i < 4; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policiesInForce", names[i]);
            row.put("clients", Figures.count(buckets[i]));
            rows.add(row);
        }
        return new ReportSection("Individuals by number of policies in force", columns, rows, Map.of());
    }

    private ReportSection schemes(UUID tenantId, ReportFilter f) {
        List<ReportColumn> columns = List.of(col("policyNumber", "Scheme", TEXT), col("holder", "Holder", TEXT),
            col("productCategory", "Product category", TEXT), col("status", "Status", TEXT),
            col("livesCovered", "Lives covered", COUNT), col("joined", "Joined in period", COUNT),
            col("exited", "Left in period", COUNT), col("currency", "Currency", TEXT), col("sumAssured", "Total cover", MONEY),
            col("annualisedPremium", "Annualised premium", MONEY));
        List<Map<String, String>> rows = queries.schemes(tenantId, f.asOf(), f.from(), f.to(), f).stream().map(r -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policyNumber", r.policyNumber());
            row.put("holder", r.holder() == null ? "" : r.holder());
            row.put("productCategory", r.productCategory() == null ? "" : r.productCategory());
            row.put("status", r.status() == null ? "NOT YET ACTIVE" : r.status());
            row.put("livesCovered", Figures.count(r.lives() == null ? 0 : r.lives()));
            row.put("joined", Figures.count(r.joined()));
            row.put("exited", Figures.count(r.exited()));
            row.put("currency", r.currency());
            row.put("sumAssured", Figures.money(r.totalCovered()));
            row.put("annualisedPremium", Figures.money(r.annualised()));
            return row;
        }).toList();
        return new ReportSection("Group and corporate schemes on " + f.asOf(), columns, rows, Sections.totals(columns, rows));
    }
}
