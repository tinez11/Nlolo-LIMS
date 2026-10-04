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

    /**
     * Prepare a free-look cancellation: the window is checked, the premiums read and the itemised
     * deductions priced. Nothing happens to the policy until a second person approves it.
     */
    FreeLookCancellationView requestFreeLook(String policyNumber, String payeeRef,
                                             List<FreeLookDeductionInput> deductions, String requestedBy);

    /**
     * Release it: the policy is voided from inception, the schedule withdrawn, and the refund
     * requested from the disbursement rail.
     */
    FreeLookCancellationView approveFreeLook(UUID cancellationId, String approver);

    /** The policy's most recent cancellation in any status, so a second person can find one. */
    java.util.Optional<FreeLookCancellationView> findFreeLook(String policyNumber);

    /** Payment runs, newest first. Runs are few -- one a day per tenant -- so this is not paged. */
    List<PaymentRunView> listRuns();

    PaymentRunView getRun(UUID paymentRunId);

    List<PayoutInstalmentView> runInstalments(UUID paymentRunId);

    /**
     * Release a PREPARED run: every instalment in it reaches APPROVED and its payment is requested.
     *
     * <p>One signature, not two. The stream's first instalment already carried a reviewer and a
     * separate approver; what stands behind these is the proof-of-life interval, which suspends the
     * stream the moment it lapses. Decision Q7, and deliberate rather than an omission.
     */
    PaymentRunView approveRun(UUID paymentRunId, String approver);

    /**
     * Fresh proof that the life assured is alive. Restarts the stream's clock and releases the
     * instalments the overdue proof held, so long as premiums are not separately behind.
     */
    void recordProofOfLife(UUID streamId, ProofOfLifeMethod method, UUID documentId, String recordedBy);

    /**
     * What a death claim may pay on this policy, given the sum-assured ceiling policy computed.
     *
     * <p>Unchanged for a policy whose version authored no payout terms. Otherwise: minus the
     * survival benefits already paid where the product deducts them (guide §7), then the higher of
     * that and the product's percentage of premiums collected (guide §6).
     */
    java.math.BigDecimal deathBenefitCeiling(String policyNumber, java.math.BigDecimal sumAssuredCeiling);

    /**
     * The death ceiling as at the date of death (product step 3). For an account-valued version it is
     * the account at the death, or the premium floor if higher, plus contributions paid after it.
     * Every other version: exactly {@link #deathBenefitCeiling(String, java.math.BigDecimal)}.
     */
    java.math.BigDecimal deathBenefitCeiling(String policyNumber, java.math.BigDecimal sumAssuredCeiling,
                                             java.time.LocalDate dateOfDeath);

    // ---- An annuity's income for life (product step 5) ----

    /**
     * A pension's lump sum (D2): one COMMUTATION instalment due on the vesting date, its payee named
     * at review as a maturity's is. Idempotent per policy: a second call returns the same id.
     */
    UUID scheduleCommutation(String policyNumber, java.time.LocalDate dueDate, java.math.BigDecimal amount, String currency);

    /**
     * Open an annuity's stream: no end date, the locked base escalated from the first payment, twelve
     * months expanded ahead and rolled forward from there. Idempotent per policy: a second call
     * returns the stream already open.
     */
    UUID openAnnuityStream(String policyNumber, java.time.LocalDate firstDue, String frequency,
                           java.math.BigDecimal baseAmount, String currency, java.math.BigDecimal escalationPercent,
                           int proofOfLifeIntervalMonths);

    /** Nothing further is owed: every instalment due after {@code afterDate} is withdrawn, and the stream ends. */
    void endAnnuityStream(String policyNumber, java.time.LocalDate afterDate, String reason);

    /** A joint annuity's first death: every instalment due on or after {@code fromDate} pays {@code percent}. */
    void reduceAnnuityStream(String policyNumber, java.time.LocalDate fromDate, java.math.BigDecimal percent);

    /**
     * The last death inside a guarantee: instalments due on or after {@code fromDate} up to
     * {@code untilDate} go to {@code payeeRef} (null when none is known -- each then waits for a
     * reviewer to enter one), and none after. Proof of life stops.
     */
    void redirectAnnuityStream(String policyNumber, java.time.LocalDate fromDate, java.time.LocalDate untilDate, String payeeRef);

    /** Gross annuity income paid on the policy so far. */
    java.math.BigDecimal annuityPaidGross(String policyNumber);

    /** Gross annuity income paid for due dates after {@code date}. */
    java.math.BigDecimal annuityPaidGrossDueAfter(String policyNumber, java.time.LocalDate date);

    /**
     * What the stream will pay for due dates after {@code fromExclusive} up to {@code toInclusive},
     * from its own figures (including instalments not yet expanded). Zero when there is no stream.
     */
    java.math.BigDecimal annuityScheduledGrossBetween(String policyNumber, java.time.LocalDate fromExclusive,
                                                      java.time.LocalDate toInclusive);
}
