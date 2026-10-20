package tz.co.nlolo.lifeplatform.underwriting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationApi;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Discards every confirmed quotation that never reached the underwriter by the end of the civil day it was confirmed
 * on (user, 2026-10-20: "only those confirmed and went to the underwriter remain"). The agent's own cancel discards
 * at once; this catches the browser closed half-way through onboarding. Hourly, so a quotation confirmed yesterday is
 * gone early today, Africa/Dar_es_Salaam -- never less than an hour after it was confirmed (see {@link #cutoff}).
 */
@Component
public class UnsubmittedQuotationSweep {

    private static final Logger log = LoggerFactory.getLogger(UnsubmittedQuotationSweep.class);
    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final AcceptedQuotationApi quotations;

    public UnsubmittedQuotationSweep(AcceptedQuotationApi quotations) {
        this.quotations = quotations;
    }

    @Scheduled(fixedDelayString = "${underwriting.quotation-sweep-interval-ms:3600000}",
        initialDelayString = "${underwriting.quotation-sweep-interval-ms:3600000}")
    public void drain() {
        int discarded = quotations.discardUnsubmittedBefore(cutoff(java.time.Instant.now()));
        if (discarded > 0) {
            log.info("Discarded {} confirmed quotation(s) never submitted as a proposal", discarded);
        }
    }

    /**
     * Confirmed before today began -- and at least {@link #GRACE} ago, so a quotation confirmed at 23:50 is not swept
     * from under an agent still onboarding the customer at 00:10, whose submission would then find it gone.
     */
    static java.time.Instant cutoff(java.time.Instant now) {
        java.time.Instant startOfToday = LocalDate.ofInstant(now, CIVIL_ZONE).atStartOfDay(CIVIL_ZONE).toInstant();
        java.time.Instant graceEnds = now.minus(GRACE);
        return startOfToday.isBefore(graceEnds) ? startOfToday : graceEnds;
    }

    static final java.time.Duration GRACE = java.time.Duration.ofHours(1);
}
