package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * One page of premiums to follow up, with the counts behind the page's three tabs (due today, due soon, overdue) for
 * the same filters -- so a tab can say how many it holds before it is opened.
 */
public record FollowUpPage(List<FollowUpRow> rows, long total, int page, int pageSize,
                           long dueToday, long dueSoon, long overdue, BigDecimal overdueOwed) {}
