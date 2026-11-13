package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What unit-linked business waits on (2026-10-27): a fund price for its second signature, a unit withdrawal for its
 * approval, and a price correction's adjustment to settle with the policyholder.
 */
@Configuration
class UnitLinkedApprovalSources {

    @Bean
    ApprovalSource fundPriceApprovals(FundPriceRepository prices, FundRepository funds) {
        return ApprovalSource.of("fund-price", "Fund prices", "Approve", "funds.approve", limit -> {
            UUID tenant = TenantContext.get();
            Map<UUID, String> codes = funds.findByTenantIdOrderByCode(tenant).stream()
                .collect(Collectors.toMap(Fund::getFundId, Fund::getCode));
            return ApprovalSource.Pending.of(prices.findByTenantIdAndStatusOrderByProposedAtAsc(tenant, "PROPOSED")
                .stream()
                .map(p -> new ApprovalSource.Item(p.getPriceId().toString(), null,
                    codes.getOrDefault(p.getFundId(), "Fund") + " at " + p.getPrice().stripTrailingZeros().toPlainString()
                        + " for " + ApprovalSource.day(p.getValuationDate()), null, null, p.getProposedBy(), p.getProposedAt()))
                .toList(), limit);
        });
    }

    /** A new fund and its management charge, waiting for a second person (2026-11-12, finance finding 126). */
    @Bean
    ApprovalSource fundApprovals(FundRepository funds) {
        return ApprovalSource.of("fund", "New funds", "Approve", "funds.approve",
            limit -> ApprovalSource.Pending.of(funds.findByTenantIdOrderByCode(TenantContext.get()).stream()
                .filter(Fund::isProposed)
                .map(f -> new ApprovalSource.Item(f.getCode(), null,
                    f.getCode() + " · " + f.getName() + ", management charge "
                        + f.getAnnualManagementChargePercent().stripTrailingZeros().toPlainString() + "% a year",
                    null, null, f.getCreatedBy(), f.getCreatedAt()))
                .toList(), limit));
    }

    @Bean
    ApprovalSource unitWithdrawalApprovals(UnitLinkedWithdrawalRequestRepository withdrawals) {
        return ApprovalSource.of("unit-withdrawal", "Unit-linked withdrawals", "Approve", "accounts.approve",
            limit -> ApprovalSource.Pending.of(
                withdrawals.findByTenantIdAndStatusOrderByRequestedAtAsc(TenantContext.get(), "REQUESTED").stream()
                    .map(w -> new ApprovalSource.Item(w.getWithdrawalId().toString(), w.getPolicyNumber(),
                        "Withdrawal from units to " + w.getPayeeRef(), w.getGrossAmount(), null, w.getRequestedBy(),
                        w.getRequestedAt()))
                    .toList(), limit));
    }

    /** One person settles a correction; the price behind it already had its two signatures. */
    @Bean
    ApprovalSource priceAdjustmentSettlements(PriceCorrectionAdjustmentRepository adjustments) {
        return ApprovalSource.of("price-adjustment", "Price-correction adjustments to settle", "Settle", "funds.manage",
            false, limit -> ApprovalSource.Pending.of(
                adjustments.findByTenantIdAndStatusOrderByPolicyNumber(TenantContext.get(), "OPEN").stream()
                    .map(a -> new ApprovalSource.Item(a.getAdjustmentId().toString(), a.getPolicyNumber(),
                        switch (a.getDirection()) {
                            case OWED_TO_CUSTOMER -> "Owed to the policyholder after a price correction";
                            case OWED_BY_CUSTOMER -> "Owed by the policyholder after a price correction";
                        }, a.getAmount(), null, null, null))
                    .toList(), limit));
    }
}
