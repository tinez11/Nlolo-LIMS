package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;

import java.util.ArrayList;
import java.util.List;

/** The one line under a report's title -- and the About line of every export -- saying what was asked for. */
final class FilterSummary {
    private FilterSummary() {}

    static String describe(ReportDefinitionView d, ReportFilter f, Labels labels) {
        List<String> parts = new ArrayList<>();
        if (f.asOf() != null) parts.add("As of " + f.asOf());
        if (f.compareAsOf() != null) parts.add("compared with " + f.compareAsOf());
        if (f.from() != null) parts.add((f.asOf() != null ? "Period " : "From ") + f.from() + " to " + f.to());
        if (f.cohortFrom() != null) parts.add("Activated " + f.cohortFrom() + " to " + f.cohortTo());
        if (f.monthFrom() != null && f.monthTo() != null) parts.add("Periods " + f.monthFrom() + " to " + f.monthTo());
        else if (f.monthFrom() != null) parts.add("Period " + f.monthFrom());
        if (f.accountCode() != null) parts.add("Account: " + f.accountCode());
        if (!d.groupByOptions().isEmpty()) parts.add("Grouped by " + f.groupByOr(d.groupByOptions().get(0)).label());
        if (f.productId() != null) parts.add("Product: " + labels.product(f.productId()));
        if (f.productCategory() != null) parts.add("Category: " + f.productCategory());
        if (f.agentId() != null) parts.add("Agent: " + labels.agent(f.agentId()));
        if (f.salesChannel() != null) parts.add("Channel: " + f.salesChannel());
        if (f.branchCode() != null) parts.add("Branch: " + f.branchCode());
        if (f.clientType() != null) parts.add("Client type: " + f.clientType());
        return String.join(" · ", parts);
    }
}
