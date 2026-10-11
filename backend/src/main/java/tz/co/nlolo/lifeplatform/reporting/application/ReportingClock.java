package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;

import java.time.Clock;

/**
 * The reporting module's clock, wrapped rather than declared as a {@code Clock} bean: Spring Modulith's
 * Moments autoconfiguration needs exactly one {@code Clock} in the context, and unitlinked already supplies it.
 * A second {@code Clock} bean fails context startup for every test in the suite.
 */
@Component
public class ReportingClock {

    private final Clock clock = Clock.system(CivilDates.ZONE);

    public Clock clock() {
        return clock;
    }
}
