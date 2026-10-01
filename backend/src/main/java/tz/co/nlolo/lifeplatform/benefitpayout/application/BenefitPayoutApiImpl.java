package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.api.*;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.*;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;
import tz.co.nlolo.lifeplatform.product.api.PayoutPlan;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payout engine. Tasks 3 to 7 add the acting half; this is what makes a policy's schedule
 * exist and keeps it in step with the premiums billing reports.
 */
@Service
public class BenefitPayoutApiImpl implements BenefitPayoutApi {

    /** Passed as the "after" bound to cancel EVERY instalment. Not LocalDate.MIN -- Postgres
     *  cannot bind it, and the failure would surface deep inside a listener. */
    static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);

    private final PayoutInstalmentRepository instalments;
    private final PayoutStreamRepository streams;
    private final PremiumTallyRepository tallies;
    private final ProductApi productApi;
    private final PolicyApi policyApi;

    public BenefitPayoutApiImpl(PayoutInstalmentRepository instalments, PayoutStreamRepository streams,
                                PremiumTallyRepository tallies, ProductApi productApi, PolicyApi policyApi) {
        this.instalments = instalments;
        this.streams = streams;
        this.tallies = tallies;
        this.productApi = productApi;
        this.policyApi = policyApi;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutInstalmentView> listForPolicy(String policyNumber) {
        return instalments.findByPolicyNumberOrderByDueDateAscRowOrderAsc(policyNumber).stream().map(Views::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutInstalmentView getInstalment(UUID instalmentId) {
        return Views.of(load(instalmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasScheduledMaturity(String policyNumber) {
        return instalments.existsByPolicyNumberAndKindIn(policyNumber,
            List.of(PayoutKind.MATURITY.name(), PayoutKind.RETURN_OF_PREMIUM.name()));
    }

    /**
     * {@code policy.PolicyIssued}: open the premium tally and store every instalment the version's
     * plan implies.
     *
     * <p>Idempotent on the tally's presence, because an event may be redelivered and a doubled
     * schedule would pay a customer twice. The unique index on (policy, row, date) is the second
     * line of that defence.
     */
    @Transactional
    public void expandForIssuedPolicy(String policyNumber, UUID productVersionId, LocalDate issueDate,
                                      LocalDate premiumPayingUntil, String premiumFrequency) {
        UUID tenantId = TenantContext.get();
        if (tallies.existsById(policyNumber)) {
            return;
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        tallies.save(new PremiumTally(tenantId, policyNumber, premiumFrequency, issueDate, premiumPayingUntil,
            policy.sumAssuredCurrency()));

        PayoutPlan plan = productApi.resolvePayoutPlan(productVersionId);
        if (plan.rows().isEmpty()) {
            return;
        }
        // Risk commences, not the issue date: a policy issued today may carry risk from next month,
        // and a policy year is counted from when cover actually started.
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : issueDate;

        Map<Integer, UUID> streamByRow = new HashMap<>();
        for (int order = 0; order < plan.rows().size(); order++) {
            if (plan.rows().get(order).kind() == PayoutKind.INCOME) {
                PayoutStream stream = streams.save(new PayoutStream(tenantId, policyNumber, order,
                    plan.terms().proofOfLifeIntervalMonths()));
                streamByRow.put(order, stream.getStreamId());
            }
        }
        for (ScheduleExpander.Planned p : ScheduleExpander.expand(plan, start, policy.maturityDate(), policy.sumAssuredAmount())) {
            instalments.save(new PayoutInstalment(tenantId, policyNumber, p.kind(), p.rowOrder(),
                streamByRow.get(p.rowOrder()), p.dueDate(), p.amount(), policy.sumAssuredCurrency()));
        }
    }

    /**
     * {@code billing.PremiumCollected}: add to the tally, and release anything the payment cured.
     *
     * <p>A policy with no tally is one issued before this module existed, or a group scheme --
     * neither has a schedule, so there is nothing to do and nothing to log.
     */
    @Transactional
    public void recordPremium(String policyNumber, BigDecimal amount, LocalDate paidToDate) {
        tallies.findById(policyNumber).ifPresent(tally -> {
            tally.record(amount, paidToDate);
            tallies.save(tally);
            for (PayoutInstalment held : instalments.findByPolicyNumberAndStatus(policyNumber, InstalmentStatus.ON_HOLD.name())) {
                if (tally.isPaidUpTo(held.getDueDate()) && streamAllowsRelease(held)) {
                    held.release();
                    instalments.save(held);
                }
            }
        });
    }

    /** A suspended stream's instalments stay held however current the premiums are. */
    boolean streamAllowsRelease(PayoutInstalment instalment) {
        return instalment.getStreamId() == null
            || streams.findById(instalment.getStreamId()).map(s -> s.status() != StreamStatus.SUSPENDED).orElse(true);
    }

    PayoutInstalment load(UUID instalmentId) {
        return instalments.findById(instalmentId).orElseThrow(() -> new PayoutNotFoundException(instalmentId));
    }
}
