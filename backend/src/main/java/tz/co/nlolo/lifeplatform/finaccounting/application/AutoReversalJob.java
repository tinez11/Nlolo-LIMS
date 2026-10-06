package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reverses an approved accrual on the date it named (IFRS 17 I4, spec §8, user answer Q5): every line with its side
 * swapped, in the period of that date, as a SYSTEM journal -- no second approval, since the journal was approved with
 * its reverse-on date. Once per journal ({@code auto_reversal}, finaccounting V14); a reversal whose period is LOCKED
 * waits, and the console shows it waiting.
 *
 * <p>Hourly, like the other drains; dates by the calendar in Dar es Salaam, never UTC.
 */
@Component
public class AutoReversalJob {

    static final String EVENT = "finaccounting.AutoReversal";
    private static final Logger log = LoggerFactory.getLogger(AutoReversalJob.class);

    private final JdbcTemplate jdbc;
    private final JournalEntryRepository journals;
    private final GlPostingRepository postings;
    private final FinaccountingApiImpl ledger;
    private final AccountingPeriods periods;
    private final TransactionTemplate tx;

    AutoReversalJob(JdbcTemplate jdbc, JournalEntryRepository journals, GlPostingRepository postings,
                    FinaccountingApiImpl ledger, AccountingPeriods periods, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.journals = journals;
        this.postings = postings;
        this.ledger = ledger;
        this.periods = periods;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${finaccounting.auto-reversal-interval-ms:3600000}",
        initialDelayString = "${finaccounting.auto-reversal-interval-ms:3600000}")
    public void drain() {
        drain(LocalDate.now(ManualJournalApiImpl.CIVIL));
    }

    void drain(LocalDate today) {
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM finaccounting.auto_reversals_due(?)",
                Date.valueOf(today))) {
            UUID tenantId = (UUID) row.get("tenant_id");
            UUID original = (UUID) row.get("original_journal_id");
            LocalDate on = ((Date) row.get("reverse_on")).toLocalDate();
            UUID previous = TenantContext.getOrNull();
            TenantContext.set(tenantId);
            try {
                reverse(tenantId, original, on);
            } catch (Exception e) {
                log.error("Auto-reversal of journal {} in tenant {} failed", original, tenantId, e);
            } finally {
                if (previous != null) TenantContext.set(previous); else TenantContext.clear();
            }
        }
    }

    /** @return false when the reversal waits (its period is LOCKED) */
    boolean reverse(UUID tenantId, UUID originalId, LocalDate on) {
        String period = YearMonth.from(on).toString();
        if (periods.view(period).status() == tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus.LOCKED) {
            log.warn("Auto-reversal of journal {} waits: period {} is locked", originalId, period);
            return false;
        }
        tx.executeWithoutResult(status -> {
            JournalEntry original = journals.findByJournalEntryIdAndTenantId(originalId, tenantId).orElseThrow();
            List<GlPosting> legs = postings.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(tenantId, originalId);
            JournalEntry reversal = new JournalEntry(tenantId, EVENT, originalId.toString(), period,
                original.getPolicyNumber(), "system:auto-reversal").withSource(JournalSource.SYSTEM).reversing(originalId);
            for (GlPosting leg : legs) {
                reversal.addLeg(leg.getAccountCode(),
                    leg.getDirection() == PostingDirection.DR ? PostingDirection.CR : PostingDirection.DR,
                    leg.getAmount(), leg.getCurrency(), leg.getDimensions());
            }
            UUID reversalId = ledger.postEntry(reversal).map(JournalEntry::getJournalEntryId)
                .orElseGet(() -> journals.findByTenantIdAndSourceEventAndSourceRef(tenantId, EVENT, originalId.toString())
                    .orElseThrow().getJournalEntryId());
            jdbc.update("UPDATE finaccounting.auto_reversal SET reversal_journal_id = ?, reversed_at = ?"
                    + " WHERE tenant_id = ? AND original_journal_id = ? AND reversed_at IS NULL",
                reversalId, Timestamp.from(Instant.now()), tenantId, originalId);
        });
        return true;
    }
}
