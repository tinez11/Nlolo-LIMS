package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Last year's statement for every open account, once (rule 4). Selection is SQL
 * ({@code accounts_due_annual_statement()}, which skips an account already holding a statement to
 * 31 December); filing is Java, because it uploads a document and publishes the SMS event.
 */
@Component
public class StatementDrain {

    private static final Logger log = LoggerFactory.getLogger(StatementDrain.class);

    private final AccountRepository accounts;
    private final AccumulationApiImpl api;
    private final TransactionTemplate requiresNew;

    public StatementDrain(AccountRepository accounts, AccumulationApiImpl api, PlatformTransactionManager tm) {
        this.accounts = accounts;
        this.api = api;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${accumulation.statement-interval-ms:86400000}",
        initialDelayString = "${accumulation.statement-interval-ms:86400000}")
    public void drain() {
        for (Object[] row : accounts.findDueAnnualStatementAcrossTenants()) {
            fileOne((String) row[0], (UUID) row[1]);
        }
    }

    void fileOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            requiresNew.executeWithoutResult(status -> {
                // December's interest first -- see the note below.
                api.postMonthEnds(policyNumber, LocalDate.now());
                Account account = accounts.findById(policyNumber).orElseThrow();
                LocalDate yearEnd = LocalDate.now().withDayOfYear(1).minusDays(1);
                LocalDate yearStart = yearEnd.withDayOfYear(1);
                LocalDate from = account.getOpenedOn().isAfter(yearStart) ? account.getOpenedOn() : yearStart;
                api.fileStatement(policyNumber, from, yearEnd, "system:annual-statement", true);
            });
        } catch (Exception e) {
            log.error("Annual statement failed for policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
