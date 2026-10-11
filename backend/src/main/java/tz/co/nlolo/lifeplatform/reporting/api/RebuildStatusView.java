package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.Instant;

/** state: IDLE, RUNNING, DONE, DONE_WITH_FAILURES (some events failed to apply; see the failure metric and
 * log), FAILED (the run itself stopped). Held in memory per tenant: a restart forgets it, the rows do not. */
public record RebuildStatusView(String state, long eventsReplayed, long eventsFailed, Instant startedAt, Instant finishedAt) {

    public static RebuildStatusView idle() {
        return new RebuildStatusView("IDLE", 0, 0, null, null);
    }
}
