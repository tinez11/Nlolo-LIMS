package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.accumulation.api.DepositPeriodStatus;
import tz.co.nlolo.lifeplatform.accumulation.api.RateDeclarationStatus;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.DepositPeriodRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.RateDeclarationRepository;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Interest an account has earned over a span, replayed from its entries -- there is no accrual
 * table, because unposted interest is not a transaction (spec §4.3).
 */
@Component
class AccountValuer {

    private final LedgerEntryRepository entries;
    private final RateDeclarationRepository rates;
    private final ProductApi productApi;
    private final DepositPeriodRepository periods;

    AccountValuer(LedgerEntryRepository entries, RateDeclarationRepository rates, ProductApi productApi,
                  DepositPeriodRepository periods) {
        this.entries = entries;
        this.rates = rates;
        this.productApi = productApi;
        this.periods = periods;
    }

    /** Rounded once, 2 dp half-even. Zero when {@code to} is before {@code from}. */
    BigDecimal interestBetween(Account account, LocalDate from, LocalDate to) {
        if (productApi.resolveDepositPlan(account.getProductVersionId()).isDeposit()) {
            // A deposit's rate is for its term, by day (D4): whatever span the caller asked about,
            // the interest is the running term's up to {@code to}, and none once the term has ended
            // (plan §R6) -- so a closing pays a term's interest once, whichever way it closes.
            return periods.findByPolicyNumberAndStatus(account.getPolicyNumber(), DepositPeriodStatus.RUNNING.name())
                .map(p -> Deposits.interestTo(p, to)).orElse(BigDecimal.ZERO.setScale(2));
        }
        if (to.isBefore(from)) {
            return BigDecimal.ZERO.setScale(2);
        }
        List<LedgerEntry> all = entries.findByPolicyNumberOrderBySeq(account.getPolicyNumber());
        BigDecimal opening = all.stream().filter(e -> e.getEffectiveDate().isBefore(from))
            .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<InterestCalculator.Movement> within = all.stream()
            .filter(e -> !e.getEffectiveDate().isBefore(from) && !e.getEffectiveDate().isAfter(to))
            .map(e -> new InterestCalculator.Movement(e.getEffectiveDate(), e.getAmount())).toList();
        RateSchedule schedule = new RateSchedule(
            rates.findByProductIdAndStatusOrderByEffectiveFrom(account.getProductId(), RateDeclarationStatus.APPROVED.name()),
            productApi.resolveAccumulationPlan(account.getProductVersionId()).guaranteedRatePercent());
        return InterestCalculator.interest(opening, within, from, to, schedule).setScale(2, RoundingMode.HALF_EVEN);
    }
}
