package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-09: the claims reports added claims.* handlers, and a rebuild -- which replays the audit journal by prefix --
 * left every claim out, because "claims." was not among its prefixes. Whatever reporting handles live, a rebuild must
 * replay.
 */
class RebuildCoversEveryHandledEventTest {

    @Test
    void everyEventTypeReportingHandlesIsOneTheRebuildReplays() {
        assertThat(ReportingEventHandlers.HANDLED).allSatisfy(type ->
            assertThat(RebuildService.PREFIXES).anySatisfy(prefix -> assertThat(type).startsWith(prefix)));
    }
}
