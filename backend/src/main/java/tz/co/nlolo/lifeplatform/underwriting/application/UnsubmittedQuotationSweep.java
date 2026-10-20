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
 * gone within the first hour of today, Africa/Dar_es_Salaam.
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
        int discarded = quotations.discardUnsubmittedBefore(LocalDate.now(CIVIL_ZONE).atStartOfDay(CIVIL_ZONE).toInstant());
        if (discarded > 0) {
            log.info("Discarded {} confirmed quotation(s) never submitted as a proposal", discarded);
        }
    }
}
