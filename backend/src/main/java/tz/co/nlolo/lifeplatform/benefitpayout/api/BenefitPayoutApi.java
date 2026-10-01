package tz.co.nlolo.lifeplatform.benefitpayout.api;

import java.util.List;
import java.util.UUID;

/**
 * The payout engine's published face. The tenant comes from {@code TenantContext} throughout, as
 * everywhere else on this platform -- no caller supplies one.
 *
 * <p>Tasks 3 to 7 add the acting methods (review, approve, payment runs, free-look); this is the
 * read surface the schedule needs to exist at all.
 */
public interface BenefitPayoutApi {

    /** Every instalment on one policy, due date then authoring order. Empty when the version has none. */
    List<PayoutInstalmentView> listForPolicy(String policyNumber);

    PayoutInstalmentView getInstalment(UUID instalmentId);

    /**
     * Whether this policy's maturity is paid on a schedule.
     *
     * <p>Claims refuses a manual MATURITY claim when it is: the money is already owed and dated,
     * and a claim filed against it would pay the same benefit twice. A policy on an older version
     * with no schedule keeps today's behaviour, which is what stops this closing a door on
     * business already sold.
     */
    boolean hasScheduledMaturity(String policyNumber);
}
