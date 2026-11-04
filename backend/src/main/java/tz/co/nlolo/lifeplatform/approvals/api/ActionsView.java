package tz.co.nlolo.lifeplatform.approvals.api;

import java.time.Instant;
import java.util.List;

/**
 * The jobs waiting for the viewer, by kind of work (2026-11-03): the bell. Grouped because one mixed list of hundreds
 * hid what kinds of work waited (the user, on seeing it).
 *
 * @param total      every job waiting for them, the bell's number
 * @param incomplete true when some module could not be read, so the number may be short -- never shown as all there is
 * @param groups     one per kind with anything waiting, the kind with the newest job first
 */
public record ActionsView(long total, boolean incomplete, List<Group> groups) {

    /** @param total every job of this kind; {@code items} the newest of them */
    public record Group(String kind, String act, long total, List<Item> items) {}

    /** {@code title} is who it is about, read first; {@code detail} what it is, short. */
    public record Item(String reference, String policyNumber, String title, String detail, Instant since) {}
}
