package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PolicyElection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PolicyRegisterBaseline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the IFRS 17 posting guide's chart (see {@link ChartOfAccountBlueprint#accounts()}) and the accounting policy
 * register's baseline (see {@link PolicyRegisterBaseline}) for one tenant, lazily, on first use.
 *
 * <p>A real deployment does not call this at all: it seeds a real, Finance-approved chart of
 * accounts per tenant during onboarding. This seeder exists so M9's own tests and any lazily
 * initialised dev/test tenant have a chart to post against without a migration hardcoding a
 * tenant id (V2 section 8 deliberately leaves the seed INSERT out of the migration for exactly
 * that reason). Since {@code finaccounting/V3} it is no longer merely convenient: {@code
 * gl_posting.account_code} is a real foreign key into {@code chart_of_account}, so a tenant with no
 * chart cannot have a posting written for it at all.
 */
@Component
public class ChartOfAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(ChartOfAccountSeeder.class);

    private final ChartOfAccountRepository chartOfAccountRepository;
    private final PolicyElectionRepository policyElectionRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ChartOfAccountSeeder(ChartOfAccountRepository chartOfAccountRepository,
                                 PolicyElectionRepository policyElectionRepository,
                                 PlatformTransactionManager transactionManager) {
        this.chartOfAccountRepository = chartOfAccountRepository;
        this.policyElectionRepository = policyElectionRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * No-op if {@code tenantId} already has any account -- never double-seeds.
     *
     * <p><b>Seeds inside its OWN transaction, and swallows a concurrent-seeding collision</b> (M9
     * final review, finding M3). Every caller is an event listener that already runs its posting in a
     * {@code REQUIRES_NEW} transaction and calls this first, so without the isolation below a
     * primary-key collision on {@code (tenant_id, account_code)} -- two events for a brand-new tenant
     * arriving concurrently, both passing the {@code existsByTenantId} check, both inserting -- would
     * abort THAT transaction in Postgres and take the journal entry down with it. Losing the posting
     * to a chart-of-accounts race would be a genuine ledger gap, and the accounts themselves are
     * identical either way, so it is worth nothing to fail over.
     *
     * <p><b>Why a {@link TransactionTemplate} rather than {@code @Transactional(REQUIRES_NEW)} on
     * this method:</b> the {@code catch} has to sit OUTSIDE the new transaction's boundary to be able
     * to recover at all. Postgres aborts the whole transaction on a constraint violation, so a
     * {@code catch} placed inside the seeding transaction could swallow the exception and then still
     * fail on the commit of an already-aborted transaction -- which is the exact defect the review
     * identified in the previous arrangement (catching in the caller), just relocated one frame
     * inwards. Wrapping the template call instead means the losing transaction is rolled back and
     * discarded before the {@code catch} runs, and the caller's own transaction was suspended the
     * whole time and is untouched. This is the same {@code REQUIRES_NEW} {@code TransactionTemplate}
     * shape the five listeners in {@code finaccounting.application} already use.
     *
     * <p>The trade-off, stated: the chart now commits independently of the posting that triggered it,
     * so a later posting failure leaves the seeded chart behind. That is harmless -- seeding is
     * idempotent reference data and the next call no-ops -- and it is the price of not letting
     * reference-data seeding be able to roll back a journal entry.
     */
    public void seedIfAbsent(UUID tenantId, String seededBy) {
        seedPolicyRegisterIfAbsent(tenantId);
        if (chartOfAccountRepository.existsByTenantId(tenantId)) {
            return;
        }
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                // Re-checked inside the new transaction: the fast path above ran in the caller's
                // transaction (or none), so it may have read a snapshot from before a concurrent
                // seeder committed. This closes the common half of the race cheaply; the catch below
                // handles the remainder, where the other thread has inserted but not yet committed
                // and no SELECT anywhere can see it.
                if (chartOfAccountRepository.existsByTenantId(tenantId)) {
                    return;
                }
                // Parents come first in blueprint order, so the byCode lookup below always hits
                // and fk_chart_of_account_parent holds at every step.
                Map<String, ChartOfAccount> byCode = new HashMap<>();
                for (ChartOfAccountBlueprint.Seed seed : ChartOfAccountBlueprint.accounts()) {
                    ChartOfAccount account = ChartOfAccount.seeded(tenantId, seed,
                        seed.parentCode() == null ? null : byCode.get(seed.parentCode()),
                        ChartOfAccountBlueprint.SEED_CURRENCY, seededBy);
                    // saveAndFlush, not save: the id is application-assigned via an @IdClass, so a
                    // plain save() defers the write past this block and a violation would surface
                    // only at commit -- outside where the catch below could see it. The same reason
                    // PartyApiImpl.registerCorporate and ProductApiImpl.createProduct flush.
                    byCode.put(seed.code(), chartOfAccountRepository.saveAndFlush(account));
                }
            });
        } catch (DataIntegrityViolationException e) {
            // Someone else seeded this tenant concurrently. The accounts are identical either way,
            // so the outcome we wanted has happened -- log it and let the caller post.
            log.info("Chart of accounts for tenant {} was seeded concurrently by another thread; "
                + "treating the collision as already-seeded", tenantId);
        }
    }

    /**
     * The accounting policy register's baseline (IFRS 17 spec §3), APPROVED, register versions 1..n -- the same
     * own-transaction, collision-tolerant shape as the chart above: two first postings for a new tenant at once both
     * try, the unique register version per tenant lets one win, and the loser's collision is the outcome it wanted.
     */
    public void seedPolicyRegisterIfAbsent(UUID tenantId) {
        if (policyElectionRepository.existsByTenantId(tenantId)) {
            return;
        }
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                if (policyElectionRepository.existsByTenantId(tenantId)) {
                    return;
                }
                Instant now = Instant.now();
                int version = 0;
                for (PolicyRegisterBaseline.Row row : PolicyRegisterBaseline.ROWS) {
                    policyElectionRepository.saveAndFlush(PolicyElection.baseline(tenantId, row.key(), row.scope(),
                        row.value(), PolicyRegisterBaseline.EFFECTIVE_FROM, row.rationale(), PolicyRegisterBaseline.SIGN_OFF,
                        ++version, now));
                }
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Accounting policy register for tenant {} was seeded concurrently; treating it as seeded", tenantId);
        }
    }
}
