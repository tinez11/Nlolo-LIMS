package tz.co.nlolo.lifeplatform.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.*;
import tz.co.nlolo.lifeplatform.billing.domain.ArrearsCase;
import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;
import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumCredit;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.infrastructure.ArrearsCaseRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BillingScheduleRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.FieldReceiptRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumCreditRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BillingApiImpl implements BillingApi {

    private static final Logger log = LoggerFactory.getLogger(BillingApiImpl.class);

    // Schedule generation pre-creates invoices this far ahead of need, mirroring the platform's
    // established convention of pre-creating partitions/reservations ahead of when they're
    // required rather than generating them lazily at due-date time (docs/06's partition-headroom
    // rationale; Module-Architecture-B1's TTL-sweep self-healing framing). 12 months covers a
    // full ANNUALLY cycle and 12x/4x a MONTHLY/QUARTERLY one.
    private static final int SCHEDULE_HORIZON_MONTHS = 12;

    /**
     * How long a lender has to settle an accepted file's premium.
     *
     * <p>Thirty days from acceptance, which is the ordinary commercial term and, more to the
     * point, is DERIVABLE: the due date has to be a pure function of the submission so a
     * redelivered acceptance event lands on the identical date.
     */
    private static final int SINGLE_PREMIUM_PAYMENT_TERM_DAYS = 30;

    private final BillingScheduleRepository billingScheduleRepository;
    private final PremiumInvoiceRepository premiumInvoiceRepository;
    /** Premium given back when a loan ends before its term. See creditUnearnedPremium. */
    private final PremiumCreditRepository premiumCreditRepository;
    private final ArrearsCaseRepository arrearsCaseRepository;
    private final FieldReceiptRepository fieldReceiptRepository;
    private final ProductApi productApi;
    /**
     * Only to name the policyholder on billing.PremiumCollected.
     *
     * <p>billing already declares policy::api, so this adds no module edge. The alternative was
     * leaving the event unable to say WHO paid -- and communication, which needs to thank them,
     * may not read policy at all.
     */
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher eventPublisher;
    private final ArrearsNotificationSweep arrearsNotificationSweep;

    public BillingApiImpl(BillingScheduleRepository billingScheduleRepository, PremiumInvoiceRepository premiumInvoiceRepository,
                           PremiumCreditRepository premiumCreditRepository,
                           ArrearsCaseRepository arrearsCaseRepository, FieldReceiptRepository fieldReceiptRepository,
                           ProductApi productApi, PolicyApi policyApi,
                           ApplicationEventPublisher eventPublisher,
                           ArrearsNotificationSweep arrearsNotificationSweep) {
        this.billingScheduleRepository = billingScheduleRepository;
        this.premiumInvoiceRepository = premiumInvoiceRepository;
        this.premiumCreditRepository = premiumCreditRepository;
        this.arrearsCaseRepository = arrearsCaseRepository;
        this.fieldReceiptRepository = fieldReceiptRepository;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.eventPublisher = eventPublisher;
        this.arrearsNotificationSweep = arrearsNotificationSweep;
    }

    @Override
    public Page<ArrearsCaseView> searchArrears(Integer minDunningLevel, Boolean resolved, Pageable pageable) {
        UUID tenantId = TenantContext.get();

        // The same opportunistic sweep listInvoices runs, and for a stronger reason: this IS the
        // collections screen, so it is the one read where a stale "not yet notified" gap is the
        // thing being looked at. Failure is swallowed for the same reason -- a catch-up must not
        // take the queue down.
        try {
            arrearsNotificationSweep.publishPending(tenantId);
        } catch (RuntimeException e) {
            log.warn("Arrears notification sweep failed for tenant {}; the arrears queue continues", tenantId, e);
        }

        Page<ArrearsCase> cases = arrearsCaseRepository.search(tenantId, minDunningLevel, resolved, pageable);
        if (cases.isEmpty()) {
            return cases.map(c -> toView(c, null));
        }

        // One invoice query for the whole page. An arrears case carries an invoiceId and no
        // money, and resolving each row's amount individually would be a query per row to draw
        // one screen -- the same N+1 this class had in listInvoices.
        Map<UUID, PremiumInvoice> invoicesById = premiumInvoiceRepository
            .findByTenantIdAndInvoiceIdIn(tenantId,
                cases.getContent().stream().map(ArrearsCase::getInvoiceId).toList())
            .stream()
            .collect(Collectors.toMap(PremiumInvoice::getInvoiceId, i -> i, (a, b) -> a));

        return cases.map(c -> toView(c, invoicesById.get(c.getInvoiceId())));
    }

    /**
     * A queue row. The invoice may be absent only if it were deleted, which nothing on this
     * platform does -- a waived or paid invoice keeps its row -- so the money fields are null
     * rather than defaulted to zero. A zero here would read as "nothing owed", which is the one
     * thing this screen must never say by accident.
     */
    private ArrearsCaseView toView(ArrearsCase arrearsCase, PremiumInvoice invoice) {
        return new ArrearsCaseView(
            arrearsCase.getArrearsCaseId(), arrearsCase.getPolicyNumber(), arrearsCase.getInvoiceId(),
            arrearsCase.getDunningLevel(), arrearsCase.getLastNotifiedDunningLevel(),
            arrearsCase.getOpenedAt(), arrearsCase.getResolvedAt(),
            invoice != null ? invoice.getAmount() : null,
            invoice != null ? invoice.getCurrency() : null,
            invoice != null ? invoice.getDueDate() : null,
            invoice != null ? InvoiceStatus.valueOf(invoice.getStatus()) : null);
    }

    @Override
    public Page<FieldReceiptView> searchFieldReceipts(String status, Pageable pageable) {
        UUID tenantId = TenantContext.get();

        // Same catch-up as the other two queues, and the one that matters most for this screen:
        // a receipt the SQL sweep flipped to RECONCILIATION_OVERDUE has not raised its staff
        // alert until the Java half publishes, so the reconciliation queue is exactly where a
        // pending announcement should be flushed.
        try {
            arrearsNotificationSweep.publishPending(tenantId);
        } catch (RuntimeException e) {
            log.warn("Arrears notification sweep failed for tenant {}; the receipt queue continues", tenantId, e);
        }

        return fieldReceiptRepository.search(tenantId, status, pageable).map(BillingApiImpl::toView);
    }

    @Override
    @Transactional
    public FieldReceiptView reconcileFieldReceipt(UUID receiptId, String reconciledBy) {
        UUID tenantId = TenantContext.get();
        FieldReceipt receipt = fieldReceiptRepository.findByReceiptIdAndTenantId(receiptId, tenantId)
            .orElseThrow(() -> new FieldReceiptNotFoundException(receiptId));

        // Re-reconciling is a no-op inside the aggregate, and the event below would then announce
        // a state change that did not happen -- so the publish is skipped too, not just the write.
        // An already-reconciled receipt returns unchanged rather than 409ing: two finance officers
        // clearing the same queue row is a race, not an error.
        if ("RECONCILED".equals(receipt.getStatus())) {
            return toView(receipt);
        }

        receipt.reconcile();
        fieldReceiptRepository.save(receipt);

        // `reconciledBy` lives on the event rather than the row: field_receipt has no actor
        // column, and DomainEventAuditListener writes every envelope to audit_log, which is
        // exactly how waiveInvoice records who waived an invoice.
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.FieldReceiptReconciled", tenantId,
            Map.of("receiptId", receipt.getReceiptId(),
                   "policyNumber", receipt.getPolicyNumber(),
                   "amount", Map.of("amount", receipt.getAmount().toPlainString(),
                                    "currencyCode", receipt.getCurrency()),
                   "reconciledAt", receipt.getReconciledAt().toString(),
                   "reconciledBy", reconciledBy)));
        return toView(receipt);
    }

    private static FieldReceiptView toView(FieldReceipt receipt) {
        return new FieldReceiptView(receipt.getReceiptId(), receipt.getPolicyNumber(), receipt.getAgentId(),
            receipt.getAmount(), receipt.getCurrency(), receipt.getCapturedAtClient(),
            receipt.getCapturedAtServer(), receipt.getStatus(), receipt.getReconciledAt());
    }

    @Override
    public InvoiceView getNextDueInvoice(String policyNumber) {
        UUID tenantId = TenantContext.get();
        return premiumInvoiceRepository.findFirstByPolicyNumberAndTenantIdAndStatusInOrderByDueDateAsc(
                policyNumber, tenantId, List.of("DUE", "IN_GRACE"))
            .map(this::toView)
            .orElseThrow(() -> new InvoiceNotFoundException("No invoice currently due for policy " + policyNumber));
    }

    @Override
    public List<InvoiceView> listInvoices(String policyNumber, InvoiceStatus status) {
        UUID tenantId = TenantContext.get();

        /*
         * The opportunistic notification sweep, on the read that is this module's most
         * travelled path (the console loads it on every policy record).
         *
         * It runs in the CALLER's TenantContext, which is what makes it RLS-safe -- see
         * ArrearsNotificationSweep for why a @Scheduled cross-tenant sweep cannot work on this
         * platform, and for how long this sat written, tested and uncalled.
         *
         * Hooked to a READ, not a write, deliberately: the policies that need chasing are
         * exactly the ones nobody is transacting against, so hanging the catch-up off a
         * payment or a waiver would never fire for a delinquent policy. The sweep is
         * per-TENANT, so any staff member opening any policy carries the whole tenant's
         * backlog forward.
         *
         * Failure is swallowed on purpose. A catch-up that cannot run must not take a
         * billing read down with it -- somebody looking at an invoice has a job to do, and
         * the sweep is self-healing by nature: the next read tries again. It is logged at
         * WARN rather than silently, because a sweep failing every time is a real problem
         * that would otherwise never surface.
         */
        try {
            arrearsNotificationSweep.publishPending(tenantId);
        } catch (RuntimeException e) {
            log.warn("Arrears notification sweep failed for tenant {}; the invoice read continues", tenantId, e);
        }

        List<PremiumInvoice> invoices = status == null
            ? premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)
            : premiumInvoiceRepository.findByPolicyNumberAndTenantIdAndStatusIn(policyNumber, tenantId, List.of(status.name()));
        if (invoices.isEmpty()) {
            return List.of();
        }

        /*
         * One arrears query for the whole list, not one per invoice.
         *
         * `toView` resolves each invoice's dunning level through its own repository call, so
         * this method was a straight N+1: a policy with two hundred invoices made two hundred
         * arrears lookups to draw one panel, and the seeded tenant has policies well past
         * that. Same "resolve the page in one query" shape the group-scheme member roll
         * already uses for its benefits.
         */
        Map<UUID, Integer> dunningByInvoice = arrearsCaseRepository
            .findByTenantIdAndInvoiceIdInAndResolvedAtIsNull(tenantId,
                invoices.stream().map(PremiumInvoice::getInvoiceId).toList())
            .stream()
            .collect(Collectors.toMap(ArrearsCase::getInvoiceId, ArrearsCase::getDunningLevel, (a, b) -> a));

        return invoices.stream().map(invoice -> toView(invoice, dunningByInvoice.get(invoice.getInvoiceId()))).toList();
    }

    @Override
    public InvoiceView getInvoice(UUID invoiceId) {
        UUID tenantId = TenantContext.get();
        PremiumInvoice invoice = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        return toView(invoice);
    }

    @Override
    @Transactional
    public InvoiceView waiveInvoice(UUID invoiceId, String reason, String waivedBy) {
        UUID tenantId = TenantContext.get();
        PremiumInvoice invoice = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        invoice.waive(reason);
        premiumInvoiceRepository.save(invoice);
        arrearsCaseRepository.findByInvoiceIdAndTenantIdAndResolvedAtIsNull(invoiceId, tenantId)
            .ifPresent(ArrearsCase::resolve);
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.InvoiceWaived", tenantId,
            Map.of("invoiceId", invoiceId, "policyNumber", invoice.getPolicyNumber(), "reason", reason, "waivedBy", waivedBy)));
        return toView(invoice);
    }

    @Override
    @Transactional
    public void requestPaymentForInvoice(UUID invoiceId, String payerRef, String idempotencyKey) {
        UUID tenantId = TenantContext.get();
        // Review fix (I1): validated here as well as at the HTTP layer, not only there. This is a
        // published API method on BillingApi, so a future non-HTTP caller (a batch collection run,
        // a scheduled retry) must hit the same rule; and a blank key silently forwarded to payment
        // would be rejected there by PaymentRequestListener.requireKey inside an AFTER_COMMIT
        // listener, where the exception is swallowed and logged -- i.e. it would look like a
        // successful request that reached the rail zero times, which is the exact failure shape
        // this whole fix exists to eliminate.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required to request payment for an invoice: "
                + "the same key means the same attempt (deduped), a new key means a new attempt");
        }
        PremiumInvoice invoice = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PaymentRequested", tenantId,
            Map.of("invoiceId", invoiceId,
                   "payerRef", payerRef,
                   "amount", Map.of("amount", invoice.getAmount().toPlainString(),
                                    "currencyCode", invoice.getCurrency()),
                   // NOT invoiceId.toString() -- see BillingApi.requestPaymentForInvoice's javadoc.
                   // payment's registry PK is (tenant_id, idempotency_key), so whatever lands here
                   // is the ONLY thing standing between "safe duplicate, drop it" and "genuine
                   // retry, send it".
                   "idempotencyKey", idempotencyKey)));
    }

    @Override
    @Transactional
    public InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference) {
        UUID tenantId = TenantContext.get();
        PremiumInvoice invoice = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        String statusBefore = invoice.getStatus();
        invoice.applyPayment(amount);
        premiumInvoiceRepository.save(invoice);
        arrearsCaseRepository.findByInvoiceIdAndTenantIdAndResolvedAtIsNull(invoiceId, tenantId)
            .ifPresent(ArrearsCase::resolve);
        // M7: until now this method recorded a premium as paid and published NOTHING at all, so no
        // module could react to a premium actually being collected -- payment.PaymentConfirmed
        // carries only sourceRef = invoiceId, with no policyNumber, which is why `distribution`
        // could not attribute renewal commission from it and `finaccounting` (M9) will need this
        // event too.
        //
        // Fires ONLY on the DUE/IN_GRACE/PARTIALLY_PAID -> PAID edge, deliberately:
        //   * applyPayment is a no-op that writes nothing when the invoice is already PAID or
        //     WAIVED (PremiumInvoice:91-93), so publishing unconditionally would announce a
        //     collection that provably never touched amountPaid -- e.g. a late payment against a
        //     waived invoice, which aPaymentAgainstAWaivedInvoiceLeavesItWaived pins as a real
        //     path. That false event would accrue commission on money that never arrived.
        //   * a PARTIALLY_PAID under-payment is not yet a collected premium. Consumers dedupe on
        //     invoiceId, so emitting on the first partial would attribute commission to the part
        //     amount and silently swallow the completing top-up. Waiting for PAID keeps invoiceId
        //     a sound idempotency key and keeps the event's meaning unambiguous: this invoice's
        //     premium is now fully collected. (User-approved fork, 2026-08-17.)
        boolean becamePaid = !"PAID".equals(statusBefore) && "PAID".equals(invoice.getStatus());
        if (becamePaid) {
            // The invoice's OWN amount/currency, not the `amount`/`currency` arguments: an
            // overpayment leaves amountPaid above the premium due, and commission must follow the
            // premium, not the surplus. (This method has always ignored both arguments beyond
            // applyPayment's running total; the invoice is the authoritative record.)
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumCollected", tenantId,
                Map.of("invoiceId", invoiceId,
                       "policyNumber", invoice.getPolicyNumber(),
                       // WHO paid, not just what was paid. Carried because the consumer that
                       // needs it most cannot look it up: communication thanks the customer for
                       // this payment and may not depend on policy. An event naming only the
                       // contract would reach it with nobody to tell -- the same gap
                       // policy.PolicyNotTakenUp had, and closed the same way.
                       "policyholderPartyId", policyApi.getPolicy(invoice.getPolicyNumber()).policyholderPartyId(),
                       "amount", Map.of("amount", invoice.getAmount().toPlainString(),
                                        "currencyCode", invoice.getCurrency()),
                       "collectedAt", Instant.now().toString())));
        }
        return toView(invoice);
    }

    @Override
    @Transactional
    public FieldReceiptResult captureFieldReceipt(UUID agentId, String policyNumber, BigDecimal amount, String currency,
                                                   String clientIdempotencyKey, Instant capturedAtClient) {
        UUID tenantId = TenantContext.get();
        // Idempotency: the composite (tenant_id, client_idempotency_key) unique index (Task 1's
        // billing/V2 migration) is the real dedup mechanism -- check-then-insert here is a
        // convenience early-return, not the source of truth; a genuine race between two
        // identical concurrent requests would still be caught by the DB constraint, surfacing
        // as an uncaught DataIntegrityViolationException (a 500, not a clean idempotent 202).
        // Verified this has no closed precedent to point to elsewhere on this platform --
        // `Idempotency-Key` is "accepted, not enforced" everywhere else too (deferred to M5) --
        // so this is the same class of accepted, deferred gap, not a regression from a fixed one.
        var existing = fieldReceiptRepository.findByTenantIdAndClientIdempotencyKey(tenantId, clientIdempotencyKey);
        if (existing.isPresent()) {
            return new FieldReceiptResult(existing.get().getReceiptId(), existing.get().getStatus());
        }
        FieldReceipt receipt = new FieldReceipt(tenantId, policyNumber, agentId, amount, currency, clientIdempotencyKey, capturedAtClient);
        fieldReceiptRepository.save(receipt);
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.FieldReceiptCaptured", tenantId,
            Map.of("receiptId", receipt.getReceiptId(), "policyNumber", policyNumber,
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
                   "capturedAt", receipt.getCapturedAtServer().toString(), "agentId", agentId)));
        return new FieldReceiptResult(receipt.getReceiptId(), receipt.getStatus());
    }

    // ---- PolicyEventListener entry points (package-private -- called only from this module's
    // own event listener, never from the REST layer or another module) ----

    @Transactional
    void generateScheduleForNewPolicy(UUID tenantId, String policyNumber, UUID productVersionId,
                                       LocalDate issueDate, BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency) {
        LocalDate firstDueDate = nextPeriodStart(issueDate, premiumFrequency);
        BillingSchedule schedule = new BillingSchedule(tenantId, policyNumber, premiumFrequency, premiumAmount, premiumCurrency, firstDueDate);
        billingScheduleRepository.save(schedule);
        generateInvoicesAhead(tenantId, schedule, productVersionId, issueDate);
    }

    @Transactional
    void regenerateScheduleForEndorsement(UUID tenantId, String policyNumber, UUID productVersionId, LocalDate effectiveDate) {
        // Genuinely a no-op for M4: policy.PolicyEndorsed fires for EVERY endorsement type
        // (policy.api.PolicyApi.EndorsementInput.endorsementType is an unrestricted free-form
        // string -- an address change endorses just as much as a premium change would), and no
        // endorsement type in this milestone's scope carries a new premiumAmount/premiumFrequency
        // to regenerate against. An earlier draft of this method unconditionally TERMINATED the
        // active schedule here with nothing to replace it -- that is not a no-op, it silently
        // stops every future invoice for any policy after its very first endorsement of any
        // kind. Left as a real, callable method (rather than deleted) so a future endorsement
        // type that DOES change premium terms has a home to implement termination + regeneration
        // together, atomically, once productVersionId/effectiveDate carry real values instead of
        // the null/null PolicyEventListener passes today.
    }

    @Transactional
    void pauseScheduleForSuspension(UUID tenantId, String policyNumber) {
        billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .ifPresent(schedule -> {
                schedule.suspend();
                billingScheduleRepository.save(schedule);
            });
    }

    @Transactional
    void resumeScheduleAfterSuspension(UUID tenantId, String policyNumber, UUID productVersionId) {
        billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "SUSPENDED")
            .ifPresent(schedule -> {
                schedule.reactivate();
                billingScheduleRepository.save(schedule);
                generateInvoicesAhead(tenantId, schedule, productVersionId, schedule.getNextDueDate());
            });
    }

    /**
     * One invoice for one accepted enrolment file.
     *
     * <p>Not scheduled, because a single premium has no cycle to sit on: this is the whole
     * charge for the borrowers that file enrolled, and the next file raises its own.
     *
     * <p><b>Idempotent on redelivery.</b> {@code policy.EnrolmentAccepted} is consumed by an
     * AFTER_COMMIT listener and AFTER_COMMIT listeners get redelivered, so a second delivery
     * must not charge the lender twice. {@code ux_premium_invoice_per_submission} is the
     * guarantee; this catches the violation and returns the existing invoice rather than
     * throwing, because a listener that throws on redelivery is a listener that retries for
     * ever.
     *
     * @param acceptedOn the date the FILE was accepted, from the event. The due date is
     *     derived from it rather than from this machine's clock: the unique index has to
     *     include due_date (premium_invoice is partitioned on it), so a redelivery that
     *     computed a different date would slip past the index and double-charge.
     */
    @Transactional
    UUID raiseSinglePremiumInvoice(UUID tenantId, String policyNumber, UUID enrolmentSubmissionId,
                                    BigDecimal amount, String currency, LocalDate acceptedOn) {
        LocalDate dueDate = acceptedOn.plusDays(SINGLE_PREMIUM_PAYMENT_TERM_DAYS);

        // The grace period comes from the product for a scheduled invoice, read off the version
        // the policy was issued on. There is no such lookup here on purpose: a single premium
        // that goes unpaid is a collections matter with one lender, not a lapse affecting four
        // hundred borrowers, so the grace window is the payment term itself.
        LocalDate graceEnd = dueDate;

        // ASKED BEFORE INSERTING, not recovered afterwards.
        //
        // The obvious shape -- insert, catch the unique violation, look up what is already
        // there -- cannot work here, and the reason is worth writing down. A failed statement
        // poisons the whole Postgres transaction ("current transaction is aborted, commands
        // ignored until end of transaction block"), so the recovery query inside the catch
        // block fails too. Nothing can be read back until this transaction rolls back.
        //
        // Matching the constraint by name would not have helped either, and would have been a
        // second bug: premium_invoice is PARTITIONED, so Postgres reports the violation against
        // the PARTITION's auto-generated index -- premium_invoice_2026_tenant_id_enrolment_
        // submission_id_due__idx -- never against ux_premium_invoice_per_submission. A guard
        // matching the parent's name silently never fires.
        Optional<PremiumInvoice> alreadyRaised = premiumInvoiceRepository
            .findByTenantIdAndEnrolmentSubmissionId(tenantId, enrolmentSubmissionId);
        if (alreadyRaised.isPresent()) {
            UUID existing = alreadyRaised.get().getInvoiceId();
            log.info("Enrolment submission {} was already invoiced as {} -- redelivered event, "
                + "not charging policy {} again", enrolmentSubmissionId, existing, policyNumber);
            return existing;
        }

        // ux_premium_invoice_per_submission remains the guarantee, and it is a real one rather
        // than decoration: two SIMULTANEOUS deliveries can both pass the check above, and the
        // loser's insert fails, aborting its own REQUIRES_NEW transaction. The listener logs
        // that and moves on. The lender is charged once either way, which is the property that
        // actually matters -- pretending to recover from the race would be the thing that
        // risked charging them twice.
        PremiumInvoice invoice = premiumInvoiceRepository.save(
            PremiumInvoice.forEnrolmentFile(tenantId, enrolmentSubmissionId, policyNumber,
                dueDate, amount, currency, graceEnd));

        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumInvoiceGenerated", tenantId,
            Map.of("invoiceId", invoice.getInvoiceId(), "policyNumber", policyNumber,
                   "dueDate", dueDate.toString(),
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency))));

        log.info("Raised single-premium invoice {} of {} {} for enrolment submission {} on policy {}",
            invoice.getInvoiceId(), amount.toPlainString(), currency, enrolmentSubmissionId, policyNumber);
        return invoice.getInvoiceId();
    }

    /**
     * Give back the premium a departing borrower paid for cover they never got.
     *
     * <p>A NEW row, never a reduction of the invoice it reverses. The invoice says what was
     * charged and goes on saying it; the credit says what came back off it. Netting them into a
     * single figure destroys the only trail that can settle an argument with a lender about a
     * month's charges — the same reasoning {@code CommissionAccrual} records for a clawback.
     *
     * <p>Idempotent, and checked BEFORE the insert for the reason
     * {@link #raiseSinglePremiumInvoice} is: a failed statement poisons the whole Postgres
     * transaction, so nothing can be read back after a constraint fires.
     * {@code ux_premium_credit_per_member} remains the guarantee against two simultaneous exits.
     *
     * @param enrolmentSubmissionId the file that charged this borrower, which is how the one
     *     invoice of eleven that this credit belongs against is found
     */
    @Transactional
    void creditUnearnedPremium(UUID tenantId, String policyNumber, UUID policyMemberId,
                                UUID enrolmentSubmissionId, BigDecimal amount, String currency,
                                String exitReason, LocalDate exitDate) {
        if (!premiumCreditRepository.findByTenantIdAndPolicyMemberId(tenantId, policyMemberId).isEmpty()) {
            log.info("Member {} on policy {} has already been credited -- redelivered exit, "
                + "not refunding twice", policyMemberId, policyNumber);
            return;
        }

        Optional<PremiumInvoice> invoice = premiumInvoiceRepository
            .findByTenantIdAndEnrolmentSubmissionId(tenantId, enrolmentSubmissionId);
        if (invoice.isEmpty()) {
            // The file enrolled this borrower but raised no invoice, which happens when every
            // OTHER row of it was rejected and the total came to nothing -- so there is nothing
            // to credit against. Logged rather than thrown: an AFTER_COMMIT listener that
            // throws retries for ever.
            log.warn("Member {} on policy {} is owed {} {} but enrolment submission {} raised no "
                + "invoice -- no credit recorded", policyMemberId, policyNumber,
                amount.toPlainString(), currency, enrolmentSubmissionId);
            return;
        }

        PremiumCredit credit = premiumCreditRepository.save(new PremiumCredit(tenantId,
            policyNumber, policyMemberId, invoice.get().getInvoiceId(), amount, currency,
            exitReason, exitDate));

        // The ONLY input to the commission clawback. A refund and its clawback must not be
        // separable: without the matching reversal the insurer returns the premium while the
        // bank keeps commission on money that was given back -- a loss on every early
        // settlement, on a product whose settlement volume the bank controls.
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumRefundDue", tenantId,
            Map.of("policyNumber", policyNumber,
                   "policyMemberId", policyMemberId,
                   "originalInvoiceId", invoice.get().getInvoiceId(),
                   // WHICH file this borrower was on, and what that whole file was charged.
                   // Distribution needs both: the accrual to reverse is the one booked for this
                   // file (a scheme has one a month for years), and the reversal is that accrual
                   // scaled by the share of the file's premium coming back.
                   "enrolmentSubmissionId", enrolmentSubmissionId,
                   "filePremiumTotal", invoice.get().getAmount().toPlainString(),
                   "exitReason", exitReason,
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency))));

        log.info("Credited {} {} to policy {} for member {} leaving on {} ({})",
            amount.toPlainString(), currency, policyNumber, policyMemberId, exitDate, exitReason);
    }

    private void generateInvoicesAhead(UUID tenantId, BillingSchedule schedule, UUID productVersionId, LocalDate fromDate) {
        ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(productVersionId);
        LocalDate cursor = schedule.getNextDueDate();
        LocalDate horizon = fromDate.plusMonths(SCHEDULE_HORIZON_MONTHS);
        List<PremiumInvoice> toCreate = new ArrayList<>();
        while (!cursor.isAfter(horizon)) {
            LocalDate graceEnd = cursor.plusDays(snapshot.gracePeriodDays());
            toCreate.add(new PremiumInvoice(tenantId, schedule.getBillingScheduleId(), schedule.getPolicyNumber(),
                cursor, schedule.getPremiumAmount(), schedule.getPremiumCurrency(), graceEnd));
            cursor = nextPeriodStart(cursor, schedule.getPremiumFrequency());
        }
        premiumInvoiceRepository.saveAll(toCreate);
        schedule.advanceNextDueDate(cursor);
        billingScheduleRepository.save(schedule);
        for (PremiumInvoice invoice : toCreate) {
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumInvoiceGenerated", tenantId,
                Map.of("invoiceId", invoice.getInvoiceId(), "policyNumber", invoice.getPolicyNumber(), "dueDate", invoice.getDueDate().toString(),
                       "amount", Map.of("amount", invoice.getAmount().toPlainString(), "currencyCode", invoice.getCurrency()))));
        }
    }

    private LocalDate nextPeriodStart(LocalDate from, String frequency) {
        Period step = switch (frequency) {
            case "MONTHLY" -> Period.ofMonths(1);
            case "QUARTERLY" -> Period.ofMonths(3);
            case "ANNUALLY" -> Period.ofYears(1);
            // Reaching here means something tried to schedule a contract that has no next
            // period. Named rather than silently stepping a year, because the symptom of
            // getting this wrong is a lender being dunned for premium nobody agreed.
            case "SINGLE" -> throw new IllegalArgumentException(
                "A SINGLE premium has no next period; this policy should never have been given a "
                    + "billing schedule (see billing.PolicyEventListener.handlePolicyIssued)");
            default -> throw new IllegalArgumentException("Unknown premium frequency: " + frequency);
        };
        return from.plus(step);
    }

    /** Single-invoice reads, which resolve their own dunning level -- one lookup for one row. */
    private InvoiceView toView(PremiumInvoice invoice) {
        Integer dunningLevel = arrearsCaseRepository.findByInvoiceIdAndTenantIdAndResolvedAtIsNull(invoice.getInvoiceId(), invoice.getTenantId())
            .map(ArrearsCase::getDunningLevel).orElse(null);
        return toView(invoice, dunningLevel);
    }

    /**
     * List reads, which resolve every dunning level in one query and pass it in.
     *
     * <p>Null means no OPEN arrears case for this invoice, which is not the same as level 0 --
     * an invoice that was never overdue and one whose arrears were resolved both land here, and
     * neither should render a dunning badge.
     */
    private InvoiceView toView(PremiumInvoice invoice, Integer dunningLevel) {
        return new InvoiceView(invoice.getInvoiceId(), invoice.getPolicyNumber(), invoice.getDueDate(),
            invoice.getAmount(), invoice.getCurrency(), InvoiceStatus.valueOf(invoice.getStatus()),
            invoice.getGracePeriodEndsAt(), dunningLevel);
    }
}
