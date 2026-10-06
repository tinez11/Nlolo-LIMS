package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountingPeriodView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.domain.AccountingPeriod;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.AccountingPeriodRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accounting periods (IFRS 17 spec §5.4): start closing, lock, and reopen through a second person. A period locks
 * only when every clearing account (9xxx) nets to zero in it and every earlier period holding postings is locked.
 */
@Component
class AccountingPeriods {

    private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final AccountingPeriodRepository periods;
    private final GlPostingRepository postings;
    private final UnpostedEvents unposted;
    private final EngineLockGate engineGate;

    AccountingPeriods(AccountingPeriodRepository periods, GlPostingRepository postings, UnpostedEvents unposted,
                      EngineLockGate engineGate) {
        this.periods = periods;
        this.postings = postings;
        this.unposted = unposted;
        this.engineGate = engineGate;
    }

    @Transactional(readOnly = true)
    AccountingPeriodView view(String period) {
        requireFormat(period);
        return periods.findByTenantIdAndPeriod(TenantContext.get(), period).map(AccountingPeriods::toView)
            .orElseGet(() -> toView(AccountingPeriod.open(TenantContext.get(), period)));
    }

    @Transactional(readOnly = true)
    List<AccountingPeriodView> list() {
        return periods.findByTenantIdOrderByPeriodDesc(TenantContext.get()).stream().map(AccountingPeriods::toView).toList();
    }

    @Transactional
    AccountingPeriodView startClosing(String period, String by) {
        AccountingPeriod p = load(period);
        p.startClosing(by, Instant.now());
        return toView(periods.save(p));
    }

    @Transactional
    AccountingPeriodView lock(String period, String by) {
        UUID tenantId = TenantContext.get();
        AccountingPeriod p = load(period);
        for (String earlier : postings.periodsWithPostingsBefore(tenantId, period)) {
            boolean locked = periods.findByTenantIdAndPeriod(tenantId, earlier)
                .map(e -> e.getStatus() == PeriodStatus.LOCKED).orElse(false);
            if (!locked) {
                throw new PeriodStateException("Period " + earlier + " must be locked first");
            }
        }
        List<Object[]> clearing = postings.nonZeroNetByAccountPrefix(tenantId, period, "9");
        if (!clearing.isEmpty()) {
            Object[] first = clearing.get(0);
            throw new PeriodStateException("Clearing account " + first[0] + " holds "
                + String.format("%,.2f", ((BigDecimal) first[1]).abs()) + " TZS in " + period
                + "; clearing accounts must return to zero before the period locks");
        }
        // Spec §7.6: an event the rules could not post is a hole in the period until it is posted or dismissed.
        int waiting = unposted.openUpTo(tenantId, period);
        if (waiting > 0) {
            throw new PeriodStateException(waiting + (waiting == 1 ? " event is" : " events are") + " not posted;"
                + " post or dismiss " + (waiting == 1 ? "it" : "them") + " before the period locks");
        }
        // IFRS 17 I5a (user answer Q4): the ledger must agree with the engine's closing figures, or the difference be
        // explained and accepted by two people -- or a replacement run make them agree.
        List<String> engine = engineGate.blocking(tenantId, period);
        if (!engine.isEmpty()) {
            throw new PeriodStateException(engine.get(0) + (engine.size() > 1 ? " (and " + (engine.size() - 1) + " more)" : ""));
        }
        p.lock(by, Instant.now());
        return toView(periods.save(p));
    }

    @Transactional
    AccountingPeriodView requestReopen(String period, String reason, String by) {
        AccountingPeriod p = load(period);
        p.requestReopen(reason, by, Instant.now());
        return toView(periods.save(p));
    }

    @Transactional
    AccountingPeriodView approveReopen(String period, String by) {
        AccountingPeriod p = load(period);
        p.approveReopen(by, Instant.now());
        return toView(periods.save(p));
    }

    /** The period's row under a write lock, or a new OPEN one (its first transition writes it). */
    private AccountingPeriod load(String period) {
        requireFormat(period);
        UUID tenantId = TenantContext.get();
        return periods.lockFor(tenantId, period).orElseGet(() -> AccountingPeriod.open(tenantId, period));
    }

    private static void requireFormat(String period) {
        if (period == null || !PERIOD.matcher(period).matches()) {
            throw new FinaccountingValidationException("A period is YYYY-MM, for example 2026-10");
        }
    }

    private static AccountingPeriodView toView(AccountingPeriod p) {
        return new AccountingPeriodView(p.getPeriod(), p.getStatus(), p.getClosingStartedBy(), p.getClosingStartedAt(),
            p.getLockedBy(), p.getLockedAt(), p.getReopenRequestedBy(), p.getReopenRequestedAt(), p.getReopenReason(),
            p.getReopenedBy(), p.getReopenedAt());
    }
}
