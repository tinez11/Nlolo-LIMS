package tz.co.nlolo.lifeplatform.benefitpayout.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
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

    /**
     * DUE -> REVIEWED: confirm where the money goes, and for a survival or income payout how the
     * life assured was confirmed alive (decision Q2).
     */
    PayoutInstalmentView review(UUID instalmentId, String payeeRef, ProofOfLifeMethod method, UUID documentId,
                                String reviewer);

    /**
     * REVIEWED -> APPROVED, by someone other than the reviewer, and the payout is requested from
     * the disbursement rail. This is the point at which money leaves.
     */
    PayoutInstalmentView approve(UUID instalmentId, String approver);

    /** The payouts register. An empty {@code statuses} means every status. Due date, then id. */
    Page<PayoutInstalmentView> search(Collection<InstalmentStatus> statuses, Pageable pageable);

    /**
     * FAILED -> APPROVED and a fresh payment request. The approval stands; only the payment is
     * tried again, under a NEW idempotency key, because payment deliberately drops a resend
     * carrying the old one.
     */
    PayoutInstalmentView retry(UUID instalmentId);
}
