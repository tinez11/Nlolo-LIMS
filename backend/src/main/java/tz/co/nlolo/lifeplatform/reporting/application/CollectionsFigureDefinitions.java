package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.FigureDefinition;
import tz.co.nlolo.lifeplatform.reporting.api.FigureGroup;

import java.util.List;

/**
 * The Collections figures explained (2026-11-11, analytics plan 2; PRD 19, decision K1). Read-only from the code: each
 * sentence was checked against the code that works the figure out --
 * {@link FollowUpQueue#figures} and {@code FollowUpQueue.scoped} (due, paid, rate, overdue now, due today),
 * {@code BillingApiImpl.invoicesDueBetween} (what fell due) and {@code BillingApiImpl.receivablesAging} (owed today).
 * A change to any of those formulas must change these words in the same commit.
 */
public final class CollectionsFigureDefinitions {

    private CollectionsFigureDefinitions() {}

    private static final String DUE_PERIOD =
        "The day each premium fell due, from the period's first day to its last, never later than today.";
    private static final String FOLLOW_UP_LEAVES_OUT =
        "Offers never accepted; premiums an agent's cash already covers; amounts not in TZS.";

    public static final FigureGroup GROUP = new FigureGroup("Collections", List.of(
        new FigureDefinition("due", "Premiums due",
            "Every premium that fell due in the period, paid or not.",
            "The premium charged on each one, added up.",
            "Waived premiums; savings and deposit accounts (they take deposits, not premiums); amounts not in TZS.",
            "Billing.", DUE_PERIOD),
        new FigureDefinition("paid", "Premiums paid",
            "Of the premiums that fell due in the period, how much is paid.",
            "Premiums due, less what is still owed on them (charged, less paid, less credited back). "
                + "Money paid early counts when the premium falls due.",
            "The same as Premiums due.", "Billing.", "As Premiums due."),
        new FigureDefinition("rate", "Collection rate",
            "Of the premiums that fell due in the period, the share that has been paid.",
            "Premiums paid ÷ premiums due, to one decimal. None when nothing fell due.",
            "The same as Premiums due.", "Billing.", "As Premiums due."),
        new FigureDefinition("overdueNow", "Overdue now",
            "Premiums past their due day that are still owed, today.",
            "Each premium still owed whose due day is before today, and what is owed on it.",
            FOLLOW_UP_LEAVES_OUT, "Billing, with product and agent from the reports' own copy.", "Today."),
        new FigureDefinition("dueToday", "Due today",
            "Premiums falling due today that are still owed.",
            "As Overdue now, for premiums due today.",
            "As Overdue now.", "As Overdue now.", "Today."),
        new FigureDefinition("owedToday", "Owed today",
            "Every unpaid premium due by today, by how late it is.",
            "Each open premium due by today (due, in grace, overdue or part paid), charged less paid less credited "
                + "back, and any other with an open arrears case. Bands: not yet overdue, 1–7, 8–14, 15–30, 31–60, "
                + "61–90, over 90 days.",
            "Offers never accepted; premiums paid or waived. Cash an agent holds is still owed until it reaches the "
                + "company.",
            "Billing.", "Today.")));
}
