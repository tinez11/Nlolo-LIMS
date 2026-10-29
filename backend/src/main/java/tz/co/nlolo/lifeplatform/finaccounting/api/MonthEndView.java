package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;
import java.util.List;

/**
 * A month's close as the finance guide's eight steps (section 8, "The month-end sequence"; IFRS 17 spec §10's checklist),
 * each read from what the platform already holds -- so finance sees where the month stands and what to do next, not a
 * list of pages. {@code current} is the first step not yet done (9 when the month is locked). {@code blockers} are why
 * the month cannot lock now, in the words the lock itself would refuse with.
 */
public record MonthEndView(String period, PeriodStatus status, int current, List<Step> steps, List<String> blockers) {

    /**
     * One step. {@code state}: DONE; WAITING -- with someone, usually a second person's decision; TODO -- finance's to
     * do next; BLOCKED -- done, but something in it stops the lock. {@code by} and {@code at} say who finished it and
     * when, where the platform records that.
     */
    public record Step(int number, String key, String title, String state, String detail, String by, Instant at) {}
}
