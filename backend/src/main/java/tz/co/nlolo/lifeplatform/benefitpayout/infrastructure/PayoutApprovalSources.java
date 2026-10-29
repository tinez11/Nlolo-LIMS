package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * What a payout waits on (2026-10-27): a due instalment for its review, a reviewed one for its approval, the day's
 * payment run for its release, a free-look refund and a withholding rule for their second signature.
 */
@Configuration
class PayoutApprovalSources {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    /** First signature: nobody asked for a due instalment, it fell due. */
    @Bean
    ApprovalSource payoutReviews(BenefitPayoutApi payouts) {
        return ApprovalSource.of("payout-review", "Payouts to review", "Review", "payouts.manage", false,
            limit -> instalments(payouts, InstalmentStatus.DUE, limit, false));
    }

    /** Second signature: the reviewer may not approve what they reviewed. */
    @Bean
    ApprovalSource payoutApprovals(BenefitPayoutApi payouts) {
        return ApprovalSource.of("payout-approval", "Payouts to approve", "Approve", "payouts.approve",
            limit -> instalments(payouts, InstalmentStatus.REVIEWED, limit, true));
    }

    /** The system assembles a run; one person releases it. */
    @Bean
    ApprovalSource paymentRunReleases(PaymentRunRepository runs) {
        return ApprovalSource.of("payment-run", "Payment runs to release", "Release", "payouts.approve", false,
            limit -> ApprovalSource.Pending.of(runs.findByTenantIdOrderByRunDateDesc(TenantContext.get()).stream()
                .filter(r -> "PREPARED".equals(r.getStatus()))
                .sorted(java.util.Comparator.comparing(r -> r.getRunDate()))
                .map(r -> new ApprovalSource.Item(r.getPaymentRunId().toString(), null,
                    "Income instalments due " + ApprovalSource.day(r.getRunDate()), null, null, null, start(r.getRunDate())))
                .toList(), limit));
    }

    @Bean
    ApprovalSource freeLookApprovals(FreeLookCancellationRepository cancellations) {
        return ApprovalSource.of("free-look", "Free-look refunds", "Approve", "payouts.approve",
            limit -> ApprovalSource.Pending.of(cancellations.findByStatusOrderByRequestedAtAsc("REQUESTED").stream()
                .map(c -> new ApprovalSource.Item(c.getCancellationId().toString(), c.getPolicyNumber(),
                    "Cancelled in the free-look period, refund to " + c.getPayeeRef(), c.getRefundAmount(),
                    c.getCurrency(), c.getRequestedBy(), c.getRequestedAt()))
                .toList(), limit));
    }

    @Bean
    ApprovalSource withholdingRuleApprovals(WithholdingRuleRepository rules) {
        return ApprovalSource.of("withholding-rule", "Withholding rules", "Approve", "payouts.approve",
            limit -> ApprovalSource.Pending.of(rules.findByTenantIdAndStatus(TenantContext.get(), "PROPOSED").stream()
                .sorted(java.util.Comparator.comparing(r -> r.getProposedAt()))
                .map(r -> new ApprovalSource.Item(r.getRuleId().toString(), null,
                    r.getRatePercent().stripTrailingZeros().toPlainString() + "% withheld from "
                        + String.join(", ", r.getPayoutKinds()).toLowerCase().replace('_', ' '),
                    null, null, r.getProposedBy(), r.getProposedAt()))
                .toList(), limit));
    }

    private static ApprovalSource.Pending instalments(BenefitPayoutApi payouts, InstalmentStatus status, int limit,
                                                      boolean reviewerAsked) {
        Page<PayoutInstalmentView> page = payouts.search(List.of(status),
            PageRequest.of(0, Math.max(1, limit), Sort.by("dueDate").ascending()));
        return new ApprovalSource.Pending(page.getTotalElements(), page.getContent().stream()
            .map(p -> new ApprovalSource.Item(p.instalmentId().toString(), p.policyNumber(),
                words(p.kind().name()) + " payout", p.currentAmount(), p.currency(),
                reviewerAsked ? p.reviewedBy() : null, start(p.dueDate())))
            .toList());
    }

    private static String words(String constant) {
        String s = constant.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static java.time.Instant start(LocalDate day) {
        return day == null ? null : day.atStartOfDay(CIVIL).toInstant();
    }
}
