package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.accumulation.api.RateDeclarationStatus;
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

    AccountValuer(LedgerEntryRepository entries, RateDeclarationRepository rates, ProductApi productApi) {
        this.entries = entries;
        this.rates = rates;
        this.productApi = productApi;
    }

    /** Rounded once, 2 dp half-even. Zero when {@code to} is before {@code from}. */
    BigDecimal interestBetween(Account account, LocalDate from, LocalDate to) {
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
