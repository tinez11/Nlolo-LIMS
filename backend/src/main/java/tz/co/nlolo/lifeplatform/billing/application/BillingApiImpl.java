package tz.co.nlolo.lifeplatform.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.*;
import tz.co.nlolo.lifeplatform.billing.domain.ArrearsCase;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceEntry;
import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;
import tz.co.nlolo.lifeplatform.billing.domain.PolicyBalance;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BalanceEntryRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PolicyBalanceRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.billing.api.WaiverRefusedException;
import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumCredit;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumReceipt;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumReceiptRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
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
    private final PremiumReceiptRepository premiumReceiptRepository;
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
    /** The policy balance (money movement step 2, billing V14). */
    private final PolicyBalanceRepository policyBalanceRepository;
    private final BalanceEntryRepository balanceEntryRepository;
    /** What a policy that charges no more premiums gives back (money movement step 3a). */
    private final BalanceWithdrawals balanceWithdrawals;

    public BillingApiImpl(BillingScheduleRepository billingScheduleRepository, PremiumInvoiceRepository premiumInvoiceRepository,
                           PremiumCreditRepository premiumCreditRepository,
                           ArrearsCaseRepository arrearsCaseRepository, FieldReceiptRepository fieldReceiptRepository,
                           ProductApi productApi, PolicyApi policyApi,
                           ApplicationEventPublisher eventPublisher,
                           ArrearsNotificationSweep arrearsNotificationSweep,
                           PremiumReceiptRepository premiumReceiptRepository,
                           PolicyBalanceRepository policyBalanceRepository, BalanceEntryRepository balanceEntryRepository,
                           BalanceWithdrawals balanceWithdrawals) {
        this.balanceWithdrawals = balanceWithdrawals;
        this.policyBalanceRepository = policyBalanceRepository;
        this.balanceEntryRepository = balanceEntryRepository;
        this.premiumReceiptRepository = premiumReceiptRepository;
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

    @Override
    public List<ReceivablesAging> receivablesAging(java.time.LocalDate asOf) {
        // The query returns only the bands that have rows; the view promises all five, in order,
        // because an empty band means "nothing is this overdue" rather than "no data".
        Map<String, Map<String, Object[]>> byCurrency = new java.util.LinkedHashMap<>();
        for (Object[] row : arrearsCaseRepository.receivablesAging(TenantContext.get(), asOf)) {
            byCurrency.computeIfAbsent((String) row[0], c -> new java.util.HashMap<>())
                .put((String) row[1], row);
        }
        return byCurrency.entrySet().stream()
            .map(entry -> new ReceivablesAging(entry.getKey(), ReceivablesAging.BUCKETS.stream()
                .map(name -> {
                    Object[] row = entry.getValue().get(name);
                    return row == null
                        ? new ReceivablesAging.Bucket(name, 0L, BigDecimal.ZERO.setScale(2))
                        : new ReceivablesAging.Bucket(name, ((Number) row[2]).longValue(),
                            ((BigDecimal) row[3]).setScale(2));
                })
                .toList()))
            .toList();
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

        return fieldReceiptRepository.search(tenantId, status, pageable).map(this::toView);
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

    private FieldReceiptView toView(FieldReceipt receipt) {
        return new FieldReceiptView(receipt.getReceiptId(), receipt.getPolicyNumber(), receipt.getAgentId(),
            receipt.getAmount(), receipt.getCurrency(), receipt.getCapturedAtClient(),
            receipt.getCapturedAtServer(), receipt.getStatus(), receipt.getReconciledAt(),
            holderOf(receipt.getPolicyNumber()), receipt.getBankedAt(), receipt.getBankedReference());
    }

    /** Who paid the agent. A policy that cannot be read only leaves the name off; the cash is still listed. */
    private UUID holderOf(String policyNumber) {
        try {
            return policyApi.getPolicy(policyNumber).policyholderPartyId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    @Transactional
    public java.util.List<FieldReceiptView> agentCash(UUID agentId) {
        UUID tenantId = TenantContext.get();
        try {
            arrearsNotificationSweep.publishPending(tenantId);
        } catch (RuntimeException e) {
            log.warn("Arrears notification sweep failed for tenant {}; the agent's cash list continues", tenantId, e);
        }
        return fieldReceiptRepository.findByTenantIdAndAgentIdAndStatusNotOrderByCapturedAtServerAsc(
            tenantId, agentId, "RECONCILED").stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public java.util.List<FieldReceiptView> markAgentCashBanked(UUID agentId, java.util.List<UUID> receiptIds,
                                                                String reference) {
        UUID tenantId = TenantContext.get();
        if (receiptIds == null || receiptIds.isEmpty()) {
            throw new IllegalArgumentException("Choose the collections you paid in");
        }
        java.util.List<FieldReceipt> receipts = new java.util.ArrayList<>();
        for (UUID id : new java.util.LinkedHashSet<>(receiptIds)) {
            FieldReceipt receipt = fieldReceiptRepository.findByReceiptIdAndTenantId(id, tenantId)
                .filter(r -> r.getAgentId().equals(agentId))
                // Another agent's collection is not this agent's to vouch for: the same 404 as one that does not exist.
                .orElseThrow(() -> new FieldReceiptNotFoundException(id));
            receipt.markBanked(reference);
            receipts.add(receipt);
        }
        fieldReceiptRepository.saveAll(receipts);
        for (FieldReceipt r : receipts) {
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.FieldReceiptBanked", tenantId, Map.of(
                "receiptId", r.getReceiptId(), "policyNumber", r.getPolicyNumber(), "agentId", agentId,
                "amount", Map.of("amount", r.getAmount().toPlainString(), "currencyCode", r.getCurrency()),
                "bankedReference", r.getBankedReference(), "bankedAt", r.getBankedAt().toString())));
        }
        return receipts.stream().map(this::toView).toList();
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
    @Transactional(readOnly = true)
    public List<ReceiptView> listReceipts(String policyNumber) {
        return premiumReceiptRepository.findByTenantIdAndPolicyNumberOrderByReceivedAtAsc(TenantContext.get(), policyNumber)
            .stream().map(r -> new ReceiptView(r.getReceiptId(), r.getInvoiceId(), r.getAmount(), r.getCurrency(),
                r.getReceivedAt(), r.getPaymentReference(), r.getPayerRef())).toList();
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

        // Credits in one query too, summed per invoice they were raised against.
        Map<UUID, BigDecimal> creditedByInvoice = premiumCreditRepository
            .findByTenantIdAndPolicyNumber(tenantId, policyNumber).stream()
            .collect(Collectors.toMap(PremiumCredit::getOriginalInvoiceId, PremiumCredit::getAmount, BigDecimal::add));

        return invoices.stream().map(invoice -> toView(invoice, dunningByInvoice.get(invoice.getInvoiceId()),
            creditedByInvoice.getOrDefault(invoice.getInvoiceId(), BigDecimal.ZERO))).toList();
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
        // 2026-11-08: a second waiver re-posted the reversal for nothing, and a paid premium has nothing to cancel.
        if (invoice.isClosed()) {
            throw new WaiverRefusedException("This premium is already " + invoice.getStatus().toLowerCase()
                + "; there is nothing left to waive");
        }
        // 2026-11-08: a proposed policy comes on cover when its premium is paid; waived, it never could.
        if (policyApi.getPolicy(invoice.getPolicyNumber()).status() == PolicyStatus.PROPOSED) {
            throw new WaiverRefusedException("This premium starts the policy's cover; a proposed policy's premium is"
                + " paid, not waived");
        }
        waive(tenantId, invoice, reason, waivedBy);
        return toView(invoice);
    }

    /**
     * The one way an invoice is waived. It resolves any arrears case and publishes billing.InvoiceWaived with the
     * amount still OUTSTANDING, which finaccounting reverses (DR 2140 unearned premium / CR 1210 receivable -- the
     * exact reverse of the invoice being raised, and premium is never earned out of 2140 before IFRS 17). Until
     * 2026-10-05 the event carried no amount and no rule booked it, so every waiver -- a finance write-off, a
     * vesting, a terminated policy's future invoices -- left its receivable on the ledger for good.
     */
    private void waive(UUID tenantId, PremiumInvoice invoice, String reason, String waivedBy) {
        // What is still owed: charged less paid less credited (money movement step 2). Charged less paid reversed a
        // credited premium twice -- once by its credit, again by the waiver.
        BigDecimal outstanding = invoice.balanceDue(creditedAgainst(invoice));
        invoice.waive(reason);
        premiumInvoiceRepository.save(invoice);
        arrearsCaseRepository.findByInvoiceIdAndTenantIdAndResolvedAtIsNull(invoice.getInvoiceId(), tenantId)
            .ifPresent(ArrearsCase::resolve);
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.InvoiceWaived", tenantId,
            Map.of("invoiceId", invoice.getInvoiceId(), "policyNumber", invoice.getPolicyNumber(), "reason", reason,
                   "waivedBy", waivedBy, "waiverRef", invoice.waiverRef(),
                   "amount", Map.of("amount", outstanding.toPlainString(), "currencyCode", invoice.getCurrency()))));
    }

    /**
     * A waiver taken back (2026-11-08): POL-3E440FE9's starting premium was waived while the policy was still
     * proposed, leaving nothing it could pay to come on cover. The invoice is owed again and
     * billing.InvoiceWaiverUndone puts back on the ledger exactly what the waiver took off, under the waiver's own
     * reference. An arrears case the waiver resolved stays resolved; the sweep opens a new one if it falls overdue.
     */
    @Override
    @Transactional
    public InvoiceView undoWaiver(UUID invoiceId, String reason, String undoneBy) {
        UUID tenantId = TenantContext.get();
        PremiumInvoice invoice = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!"WAIVED".equals(invoice.getStatus())) {
            throw new WaiverRefusedException("This premium is " + invoice.getStatus().toLowerCase().replace('_', ' ')
                + ", not waived; there is no waiver to undo");
        }
        String waiverRef = invoice.waiverRef();
        invoice.undoWaiver();
        BigDecimal owedAgain = invoice.balanceDue(creditedAgainst(invoice));
        premiumInvoiceRepository.save(invoice);
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.InvoiceWaiverUndone", tenantId,
            Map.of("invoiceId", invoice.getInvoiceId(), "policyNumber", invoice.getPolicyNumber(), "reason", reason,
                   "undoneBy", undoneBy, "waiverRef", waiverRef,
                   "amount", Map.of("amount", owedAgain.toPlainString(), "currencyCode", invoice.getCurrency()))));
        return toView(invoice);
    }

    /**
     * The policy has ended -- surrendered (a settled claim that ends it publishes the same), cancelled in free-look,
     * expired, matured, or made paid-up. The schedule ends, and every unsettled invoice for cover the policy no
     * longer gives is waived: those due on or after {@code from}, or every one when {@code from} is null (a free-look
     * cancels from inception). Invoices due BEFORE {@code from} are arrears for cover that was given, and stay open.
     */
    @Transactional
    void endBillingForTermination(UUID tenantId, String policyNumber, LocalDate from, String reason) {
        terminateScheduleForExpiry(tenantId, policyNumber);
        for (PremiumInvoice invoice : premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)) {
            if (UNSETTLED.contains(invoice.getStatus()) && (from == null || !invoice.getDueDate().isBefore(from))) {
                waive(tenantId, invoice, reason, "system");
            }
        }
        returnBalanceOnEnd(tenantId, policyNumber);
    }

    /**
     * A policy that will charge no more premiums gives its balance back (money movement step 3a): first it pays any
     * premium still owed for cover that was given, then whatever is left becomes a withdrawal waiting for finance to name
     * who receives it.
     */
    private void returnBalanceOnEnd(UUID tenantId, String policyNumber) {
        payFromBalance(tenantId, policyNumber, null, null, null, null);
        balanceWithdrawals.raiseOnPolicyEnd(tenantId, policyNumber);
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
        // WHAT IS OWED, not what was charged. This asked for the invoice's full amount, so a
        // lender whose borrower repaid early was asked to pay again the premium already credited
        // back to them -- 13,800 requested where 9,600 was owed.
        BigDecimal balance = invoice.balanceDue(creditedAgainst(invoice));
        if (balance.signum() <= 0) {
            throw new NothingOwedException("Invoice " + invoiceId + " has nothing left to pay: it is "
                + invoice.getStatus() + ", with " + invoice.getAmount().toPlainString() + " charged, "
                + (invoice.getAmountPaid() == null ? "0" : invoice.getAmountPaid().toPlainString())
                + " paid and the rest credited");
        }
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PaymentRequested", tenantId,
            Map.of("invoiceId", invoiceId,
                   // For the payments register (2026-10-27): which policy this money is for.
                   "policyNumber", invoice.getPolicyNumber(),
                   "payerRef", payerRef,
                   "amount", Map.of("amount", balance.toPlainString(),
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
        return applyConfirmedPayment(invoiceId, amount, currency, paymentReference, null);
    }

    @Override
    @Transactional
    public InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference,
                                             String payerRef) {
        return applyConfirmedPayment(invoiceId, amount, currency, paymentReference, payerRef, null);
    }

    @Override
    @Transactional
    public InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference,
                                             String payerRef, String method) {
        return applyConfirmedPayment(invoiceId, amount, currency, paymentReference, payerRef, method, null);
    }

    /**
     * Confirmed premium money (money movement step 2, 2026-11-04). On an insurance policy it is a DEPOSIT on the
     * policy's balance -- posted to the ledger at once for what arrived -- and the balance then pays the policy's
     * premiums that have fallen due, oldest first; whatever is left stays and pays the next ones. The invoice named
     * only says which policy. A savings-type policy (account basis) keeps the old road for now: the money pays that
     * invoice directly, as before (design D12, its surplus road arrives with "For: Savings").
     *
     * @param paymentId the confirmed payment's own id, the deposit's idempotency key; null from older callers, when a
     *                  stable key is made from the invoice and the reference
     */
    @Override
    @Transactional
    public InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference,
                                             String payerRef, String method, UUID paymentId) {
        UUID tenantId = TenantContext.get();
        PremiumInvoice invoice = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        // A redelivered confirmation carries the same reference: recorded and applied once (billing V10).
        if (paymentReference != null
                && premiumReceiptRepository.existsByTenantIdAndInvoiceIdAndPaymentReference(tenantId, invoiceId, paymentReference)) {
            return toView(invoice);
        }
        if (!isSavingsType(invoice.getPolicyNumber())) {
            UUID depositKey = paymentId != null ? paymentId
                : paymentReference != null
                    ? UUID.nameUUIDFromBytes((invoiceId + "|" + paymentReference).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : UUID.randomUUID();
            // Paid up to the invoice it was asked for even if that one is not yet due: whoever chose to pay next month's
            // premium early has paid it. Only what is left waits for each later premium's due date.
            deposit(tenantId, invoice.getPolicyNumber(), amount, currency != null ? currency : invoice.getCurrency(),
                depositKey, method, paymentReference, payerRef, BalanceEntry.FROM_PAYMENT, "payment", invoice.getDueDate());
            return toView(premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoiceId, tenantId).orElse(invoice));
        }
        applyToInvoice(tenantId, invoice, amount, currency, paymentReference, payerRef, method);
        return toView(invoice);
    }

    /**
     * Money applied to one invoice: the invoice's running total, a receipt row for the payment schedule, its arrears
     * case resolved, and -- on the edge to PAID -- billing.PremiumCollected. The one place an invoice is paid, whether
     * the money came straight from a confirmation (savings-type) or from the policy's balance.
     */
    private void applyToInvoice(UUID tenantId, PremiumInvoice invoice, BigDecimal amount, String currency,
                                String paymentReference, String payerRef, String method) {
        UUID invoiceId = invoice.getInvoiceId();
        String statusBefore = invoice.getStatus();
        // Credits count towards settling it -- see PremiumInvoice.applyPayment(paid, credited).
        invoice.applyPayment(amount, creditedAgainst(invoice));
        premiumInvoiceRepository.save(invoice);
        // The payment itself, for the customer's schedule: when, which reference, who paid (billing V10).
        if (amount != null && amount.signum() > 0) {
            premiumReceiptRepository.save(new PremiumReceipt(tenantId, invoice.getPolicyNumber(), invoiceId, amount,
                currency != null ? currency : invoice.getCurrency(), Instant.now(), paymentReference, payerRef));
        }
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
            Map<String, Object> collected = new java.util.HashMap<>();
            collected.put("invoiceId", invoiceId);
            collected.put("policyNumber", invoice.getPolicyNumber());
            // WHO paid, not just what was paid. Carried because the consumer that needs it most
            // cannot look it up: communication thanks the customer for this payment and may not
            // depend on policy. An event naming only the contract would reach it with nobody to
            // tell -- the same gap policy.PolicyNotTakenUp had, and closed the same way.
            collected.put("policyholderPartyId", policyApi.getPolicy(invoice.getPolicyNumber()).policyholderPartyId());
            // The invoice's OWN amount, not the arguments -- see the note above.
            collected.put("amount", Map.of("amount", invoice.getAmount().toPlainString(),
                "currencyCode", invoice.getCurrency()));
            collected.put("collectedAt", Instant.now().toString());
            // A credit-life file's invoice names its file (2026-10-30): distribution releases that file's commission,
            // held since acceptance, and books no renewal on it. Absent on every other invoice.
            if (invoice.getEnrolmentSubmissionId() != null) {
                collected.put("enrolmentSubmissionId", invoice.getEnrolmentSubmissionId());
            }
            // How far premiums are paid, with no gap, from the first due date -- the input a
            // cash-value policy needs to know which policy year it has reached. Billing owns the
            // invoices, so it is the only module that can compute contiguity honestly; null when
            // even the first invoice is unpaid (an out-of-order payment). A HashMap because the
            // payload now carries a nullable value, which Map.of forbids.
            LocalDate paidToDate = contiguousPaidToDate(invoice.getPolicyNumber(), tenantId);
            collected.put("paidToDate", paidToDate != null ? paidToDate.toString() : null);
            // Who paid, as a number: a fixed-term deposit pays back to it. Absent for a field
            // receipt, which has no number -- consumers must read a missing key as "none known".
            if (payerRef != null && !payerRef.isBlank()) {
                collected.put("payerRef", payerRef);
            }
            // How the money came in (2026-10-27): the ledger posts it to the account it reached. Absent is the
            // gateway's mobile money, as every collection was before receipts.
            collected.put("receiptMethod", method == null ? "MOBILE_MONEY" : method);
            // Which payment in full this is (money movement step 3b): 1 normally, n + 1 after a reversal reopened it n
            // times -- distribution keys the commission on it, so commission clawed back can be earned again.
            collected.put("collection", invoice.collectionNumber());
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumCollected", tenantId, collected));
        }
    }

    // ---- The policy balance (money movement step 2, billing V14) -------------------------------------------------

    /** Open invoices a balance may pay: owed, and not waived. */
    private static final java.util.Set<String> PAYABLE = java.util.Set.of("DUE", "IN_GRACE", "OVERDUE", "PARTIALLY_PAID");
    private static final java.time.ZoneId CIVIL = java.time.ZoneId.of("Africa/Dar_es_Salaam");

    /**
     * Money onto a policy's balance, once per payment, then the balance pays what has fallen due. A loan surplus was
     * already posted by policyloan (E-03-SURPLUS), so it publishes nothing to the ledger here; every other deposit
     * publishes billing.DepositReceived, which posts DR the account it reached / CR 2128.
     */
    void deposit(UUID tenantId, String policyNumber, BigDecimal amount, String currency, UUID paymentId, String method,
                 String reference, String payerRef, String source, String by, LocalDate payUpTo) {
        policyBalanceRepository.open(tenantId, policyNumber, currency);
        PolicyBalance balance = policyBalanceRepository.findForUpdate(tenantId, policyNumber).orElseThrow();
        if (!balance.getCurrency().equals(currency)) {
            throw new IllegalArgumentException("Policy " + policyNumber + " is billed in " + balance.getCurrency()
                + "; a deposit in " + currency + " cannot go onto its balance");
        }
        if (balanceEntryRepository.existsByTenantIdAndEntryTypeAndPaymentId(tenantId, BalanceEntry.DEPOSIT, paymentId)) {
            return; // a redelivered confirmation: deposited once
        }
        BalanceEntry entry = balanceEntryRepository.save(BalanceEntry.deposit(tenantId, policyNumber, amount, currency,
            paymentId, method, reference, source, by));
        if (!BalanceEntry.FROM_LOAN_SURPLUS.equals(source)) {
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.DepositReceived", tenantId,
                Map.of("entryId", entry.getEntryId(), "paymentId", paymentId, "policyNumber", policyNumber,
                       "amount", Map.of("amount", amount.setScale(2).toPlainString(), "currencyCode", currency),
                       "receiptMethod", method == null ? "MOBILE_MONEY" : method)));
        }
        payFromBalance(tenantId, policyNumber, method, reference, payerRef, payUpTo);
    }

    /**
     * A confirmed deposit naming no invoice (money movement step 5, payment's DEPOSIT purpose): onto the policy's
     * balance, posted like any other deposit, paying only what has fallen due -- none of it pays a premium early.
     */
    @Transactional
    public void depositConfirmed(String policyNumber, BigDecimal amount, String currency, UUID paymentId, String method,
                                 String reference, String payerRef) {
        deposit(TenantContext.get(), policyNumber, amount, currency, paymentId, method, reference, payerRef,
            BalanceEntry.FROM_PAYMENT, "payment", null);
    }

    /** A loan's surplus (policyloan.LoanOverpaid) onto the policy's balance -- already posted, so no ledger event. */
    @Transactional
    public void depositLoanSurplus(String policyNumber, BigDecimal amount, String currency, UUID paymentId, String method,
                                   String reference) {
        deposit(TenantContext.get(), policyNumber, amount, currency, paymentId, method, reference, null,
            BalanceEntry.FROM_LOAN_SURPLUS, "loan", null);
    }

    /**
     * Under the balance's lock, pays the policy's premiums that have fallen due (Dar es Salaam's day), oldest first,
     * while there is balance. Each payment is a PREMIUM_PAID entry and billing.PremiumPaidFromBalance (DR 2128 / CR the
     * receivable, with the levy on what was paid). A premium not yet due waits: invoices are raised a year ahead, and
     * paying next year's today would release its commission and cover-paid-to date early. BalanceAllocationDrain comes
     * back for it on its due date.
     */
    void payFromBalance(UUID tenantId, String policyNumber, String method, String reference, String payerRef,
                        LocalDate payUpTo) {
        if (policyBalanceRepository.findForUpdate(tenantId, policyNumber).isEmpty()) {
            return; // never received a deposit
        }
        BigDecimal left = balanceEntryRepository.balanceOf(tenantId, policyNumber);
        LocalDate today = LocalDate.now(CIVIL);
        LocalDate cutoff = payUpTo != null && payUpTo.isAfter(today) ? payUpTo : today;
        for (PremiumInvoice invoice : premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)) {
            if (left.signum() <= 0) break;
            if (!PAYABLE.contains(invoice.getStatus()) || invoice.getDueDate().isAfter(cutoff)) continue;
            BigDecimal owed = invoice.balanceDue(creditedAgainst(invoice));
            if (owed.signum() <= 0) continue;
            BigDecimal applied = left.min(owed);
            BalanceEntry paid = balanceEntryRepository.save(BalanceEntry.premiumPaid(tenantId, policyNumber, applied,
                invoice.getCurrency(), invoice.getInvoiceId()));
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumPaidFromBalance", tenantId,
                Map.of("entryId", paid.getEntryId(), "invoiceId", invoice.getInvoiceId(), "policyNumber", policyNumber,
                       "amount", Map.of("amount", applied.setScale(2).toPlainString(), "currencyCode", invoice.getCurrency()))));
            applyToInvoice(tenantId, invoice, applied, invoice.getCurrency(),
                reference != null ? reference : "balance:" + paid.getEntryId(), payerRef, method);
            left = left.subtract(applied);
        }
    }

    /** The drain's entry point: pay what has fallen due for one policy, in the caller's tenant. */
    @Transactional
    public void payFromBalance(String policyNumber) {
        payFromBalance(TenantContext.get(), policyNumber, null, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balanceOf(String policyNumber) {
        return balanceEntryRepository.balanceOf(TenantContext.get(), policyNumber).setScale(2);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal policyBalancesTotal() {
        return balanceEntryRepository.totalOfTenant(TenantContext.get()).setScale(2);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, BigDecimal> owedByPolicy() {
        java.util.Map<String, BigDecimal> byPolicy = new java.util.HashMap<>();
        for (Object[] row : premiumInvoiceRepository.owedByPolicy(TenantContext.get())) {
            byPolicy.put((String) row[0], (BigDecimal) row[1]);
        }
        return byPolicy;
    }

    @Override
    public UUID requestWithdrawalToMobileMoney(String policyNumber, BigDecimal amount, String phone, String requestedBy) {
        return balanceWithdrawals.request(policyNumber, amount, tz.co.nlolo.lifeplatform.PayoutRoute.mobileMoney(phone),
            requestedBy).getWithdrawalId();
    }

    /**
     * The balance, and the due date of the last premium it pays in full, walking what is still owed oldest first as
     * payFromBalance would. Premiums already due are paid as soon as money lands, so this is normally about premiums
     * still to come; it reaches only as far as the invoices raised (a year ahead).
     */
    @Override
    @Transactional(readOnly = true)
    public PolicyBalanceView balance(String policyNumber) {
        UUID tenantId = TenantContext.get();
        BigDecimal left = balanceEntryRepository.balanceOf(tenantId, policyNumber).setScale(2);
        List<PremiumInvoice> invoices = premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber,
            tenantId);
        String currency = invoices.stream().findFirst().map(PremiumInvoice::getCurrency).orElse("TZS");
        LocalDate covers = null;
        for (PremiumInvoice invoice : invoices) {
            if (!PAYABLE.contains(invoice.getStatus())) continue;
            BigDecimal owed = invoice.balanceDue(creditedAgainst(invoice));
            if (owed.signum() <= 0) continue;
            if (left.compareTo(owed) < 0) break;
            left = left.subtract(owed);
            covers = invoice.getDueDate();
        }
        return new PolicyBalanceView(policyNumber, balanceEntryRepository.balanceOf(tenantId, policyNumber).setScale(2),
            currency, covers);
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceStatementView balanceStatement(String policyNumber, int page, int size) {
        UUID tenantId = TenantContext.get();
        List<BalanceEntry> entries = balanceEntryRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId,
            policyNumber);
        List<BalanceStatementView.Line> lines = new java.util.ArrayList<>(entries.size());
        BigDecimal running = BigDecimal.ZERO;
        int seq = 0;
        for (BalanceEntry e : entries) {
            BigDecimal signed = BalanceEntry.MONEY_IN.contains(e.getEntryType()) ? e.getAmount() : e.getAmount().negate();
            running = running.add(signed);
            lines.add(new BalanceStatementView.Line(e.getEntryId(), ++seq, e.getEntryType(), e.getCreatedAt(),
                signed.setScale(2), running.setScale(2), e.getPaymentId(), e.getInvoiceId(), e.getWithdrawalId(),
                e.getReversesEntryId(), e.getMethod(), e.getReference(), e.getSource()));
        }
        java.util.Collections.reverse(lines);
        int from = Math.min(page * size, lines.size());
        int to = Math.min(from + size, lines.size());
        String currency = entries.stream().findFirst().map(BalanceEntry::getCurrency).orElse("TZS");
        return new BalanceStatementView(policyNumber, currency, lines.size(), page, size, List.copyOf(lines.subList(from, to)));
    }

    /**
     * A savings-type policy -- its product version's account basis is ACCOUNT -- keeps premiums on their own road for
     * now: its money is savings, and a policy balance would hold money that belongs in the account (design D12).
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isSavingsType(String policyNumber) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        return policy.productVersionId() != null
            && productApi.resolveAccumulationPlan(policy.productVersionId()).basis() == tz.co.nlolo.lifeplatform.product.api.ValueBasis.ACCOUNT;
    }

    @Override
    @Transactional
    public FieldReceiptResult captureFieldReceipt(UUID agentId, String policyNumber, BigDecimal amount, String currency,
                                                   String clientIdempotencyKey, Instant capturedAtClient) {
        return captureFieldReceipt(agentId, null, policyNumber, amount, currency, clientIdempotencyKey, capturedAtClient);
    }

    @Override
    @Transactional
    public FieldReceiptResult captureFieldReceipt(UUID agentId, UUID agentPartyId, String policyNumber, BigDecimal amount,
                                                   String currency, String clientIdempotencyKey, Instant capturedAtClient) {
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
        // The customer has paid (2026-11-11, the user: standard practice): the cash goes onto the policy's balance now,
        // posted Dr 1320 Due from agents, and pays the premium it was collected for -- no dunning, no lapse, cover from
        // today. Keyed on the field receipt, so its banking later finds it and pays nothing twice. Paid up to the next
        // premium owed even if it is not yet due: the agent collected that premium. Not at once (the user, 2026-11-11):
        // a savings-type policy -- savings, a fixed deposit, a pension before vesting -- whose money must not earn
        // interest the company does not hold, and an annuity, whose income locks and starts paying on its premium and
        // cannot be taken back. Their cash pays when it reaches the company; savings dunning waits meanwhile.
        if (paysOnRecording(policyNumber)) {
            LocalDate payUpTo = premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)
                .stream().filter(i -> PAYABLE.contains(i.getStatus())).map(PremiumInvoice::getDueDate).findFirst()
                .orElse(null);
            deposit(tenantId, policyNumber, amount, currency, receipt.getReceiptId(), FieldReceipt.AGENT_CASH,
                "agent-cash:" + receipt.getReceiptId(), null, BalanceEntry.FROM_PAYMENT, "agent:" + agentId, payUpTo);
        }
        Map<String, Object> captured = new HashMap<>(Map.of("receiptId", receipt.getReceiptId(), "policyNumber", policyNumber,
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
            "capturedAt", receipt.getCapturedAtServer().toString(), "agentId", agentId));
        if (agentPartyId != null) captured.put("agentPartyId", agentPartyId);
        // Who handed the cash over is told at once (receipts step 1; TIRA Code B.5(b): an intermediary acknowledges
        // "all monies received"), so communication needs the policyholder -- it can write to a party, not read a policy.
        // Never at the cash's expense: a policy that cannot be read here only means nobody is texted.
        try {
            UUID holder = policyApi.getPolicy(policyNumber).policyholderPartyId();
            if (holder != null) captured.put("policyholderPartyId", holder);
        } catch (RuntimeException e) {
            log.warn("Agent cash on {} recorded; its policyholder could not be read, so nobody is told", policyNumber, e);
        }
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.FieldReceiptCaptured", tenantId, captured));
        return new FieldReceiptResult(receipt.getReceiptId(), receipt.getStatus());
    }

    /** Whether agent cash on this policy pays its premium the moment the agent records it (2026-11-11). */
    boolean paysOnRecording(String policyNumber) {
        if (isSavingsType(policyNumber)) return false;
        try {
            return !"ANNUITY".equals(policyApi.getPolicy(policyNumber).productCategory());
        } catch (RuntimeException e) {
            return false; // a policy that cannot be read is paid only when the money arrives
        }
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<AgentCashTotal> agentCashTotals() {
        return fieldReceiptRepository.openTotalsByAgent(TenantContext.get()).stream()
            .map(row -> new AgentCashTotal((UUID) row[0], ((Number) row[1]).longValue(), (BigDecimal) row[2],
                ((String) row[3]).trim(), (Instant) row[4]))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<FieldReceiptView> fieldReceipt(UUID fieldReceiptId) {
        return fieldReceiptRepository.findByReceiptIdAndTenantId(fieldReceiptId, TenantContext.get()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean agentCashOnBalance(UUID fieldReceiptId) {
        UUID tenantId = TenantContext.get();
        return fieldReceiptRepository.findByReceiptIdAndTenantId(fieldReceiptId, tenantId)
            .filter(FieldReceipt::isOpen)
            .flatMap(r -> balanceEntryRepository.findByTenantIdAndEntryTypeAndPaymentId(tenantId, BalanceEntry.DEPOSIT,
                fieldReceiptId))
            .map(d -> balanceEntryRepository.findByTenantIdAndReversesEntryId(tenantId, d.getEntryId()).isEmpty())
            .orElse(false);
    }

    /**
     * The agent's cash reached the company (2026-11-11): it already paid the customer's premium when the agent recorded
     * it, so nothing is paid again -- the money moves from 1320 Due from agents to the account it reached
     * (billing.AgentCashBanked, Dr that account / Cr 1320), and the agent's cash is closed. Keyed on the confirmed
     * payment, so a redelivery moves it once.
     */
    @Transactional
    public void agentCashBanked(UUID fieldReceiptId, UUID paymentId, String method, String by) {
        UUID tenantId = TenantContext.get();
        FieldReceipt receipt = fieldReceiptRepository.findByReceiptIdAndTenantId(fieldReceiptId, tenantId)
            .orElseThrow(() -> new FieldReceiptNotFoundException(fieldReceiptId));
        if (!receipt.isOpen()) {
            return; // a redelivered confirmation: moved once
        }
        // The premiums this cash paid when it was recorded, each with its invoice's own amount: unit-linked buys their
        // units now, on money the company holds, not when the agent recorded it (2026-11-11, the user).
        java.util.List<Map<String, Object>> paid = premiumReceiptRepository
            .findByTenantIdAndPaymentReference(tenantId, "agent-cash:" + fieldReceiptId).stream()
            .map(PremiumReceipt::getInvoiceId).distinct()
            .flatMap(id -> premiumInvoiceRepository.findByInvoiceIdAndTenantId(id, tenantId).stream())
            .filter(i -> "PAID".equals(i.getStatus()))
            .map(i -> Map.<String, Object>of("invoiceId", i.getInvoiceId(),
                "amount", Map.of("amount", i.getAmount().toPlainString(), "currencyCode", i.getCurrency())))
            .toList();
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.AgentCashBanked", tenantId, Map.of(
            "paymentId", paymentId, "receiptId", fieldReceiptId, "policyNumber", receipt.getPolicyNumber(),
            "agentId", receipt.getAgentId(),
            "amount", Map.of("amount", receipt.getAmount().setScale(2).toPlainString(), "currencyCode", receipt.getCurrency()),
            "receiptMethod", method == null ? "CASH" : method,
            "bankedAt", Instant.now().toString(),
            "premiumsPaid", paid)));
        reconcileFieldReceipt(fieldReceiptId, by);
    }

    /** Agent cash undone for not arriving in time (2026-11-11): it reads EXPIRED; late money still closes it. */
    @Transactional
    public void expireFieldReceipt(UUID fieldReceiptId) {
        fieldReceiptRepository.findByReceiptIdAndTenantId(fieldReceiptId, TenantContext.get()).ifPresent(r -> {
            r.expire();
            fieldReceiptRepository.save(r);
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.FieldReceiptExpired", r.getTenantId(), Map.of(
                "receiptId", r.getReceiptId(), "policyNumber", r.getPolicyNumber(), "agentId", r.getAgentId(),
                "amount", Map.of("amount", r.getAmount().toPlainString(), "currencyCode", r.getCurrency()))));
        });
    }

    // ---- PolicyEventListener entry points (package-private -- called only from this module's
    // own event listener, never from the REST layer or another module) ----

    @Transactional
    void generateScheduleForNewPolicy(UUID tenantId, String policyNumber, UUID productVersionId,
                                       LocalDate issueDate, BigDecimal premiumAmount, String premiumCurrency,
                                       String premiumFrequency, LocalDate premiumPayingUntil) {
        // In advance (2026-10-08, billing V11): the first instalment is due the day cover starts, and pays for the
        // period starting then. Schedules created before this kept billing in arrears.
        BillingSchedule schedule = BillingSchedule.inAdvance(tenantId, policyNumber, premiumFrequency, premiumAmount,
            premiumCurrency, issueDate, premiumPayingUntil);
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

    /**
     * The policy's term ran out (policy.PolicyExpired). Terminate the schedule so no further
     * invoice is raised against a contract that has ended.
     *
     * <p>Terminated rather than suspended: suspension is a hold that resumes, and an expired term
     * never does. A SUSPENDED schedule is terminated here too -- an expired policy that happened to
     * be on hold still stops being billed. Only ACTIVE or SUSPENDED are touched; a schedule already
     * TERMINATED (a single-premium contract never had one at all) is left as it is, so the handler
     * is idempotent on a repeated event.
     */
    @Transactional
    void terminateScheduleForExpiry(UUID tenantId, String policyNumber) {
        billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .or(() -> billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "SUSPENDED"))
            .ifPresent(schedule -> {
                schedule.terminate();
                billingScheduleRepository.save(schedule);
            });
    }

    /** Every invoice status that is not yet settled -- what a vesting waives. */
    private static final java.util.Set<String> UNSETTLED = java.util.Set.of("DUE", "IN_GRACE", "OVERDUE", "PARTIALLY_PAID");

    /**
     * A pension vested (policy.AnnuityVested, product step 5 D2): no contribution is due again.
     * The schedule ends as at expiry, and EVERY unsettled invoice is waived -- not only those after
     * the vesting date (plan R7): billing raises invoices ahead and has no cancelled status, and one
     * left DUE could later be paid into an account that has closed. WAIVED is terminal and settled.
     */
    @Transactional
    void endForVesting(UUID tenantId, String policyNumber, LocalDate vestedOn) {
        terminateScheduleForExpiry(tenantId, policyNumber);
        String reason = "The pension vested on " + vestedOn + "; no further contributions are due";
        for (PremiumInvoice invoice : premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)) {
            if (UNSETTLED.contains(invoice.getStatus())) {
                waive(tenantId, invoice, reason, "system");
            }
        }
    }

    /**
     * The premium changed from {@code effectiveFrom} (policy.PremiumRestated, family funeral cover: a life
     * added or ended, the anniversary re-pricing).
     *
     * <p>Every instalment falling due on or after {@code effectiveFrom} that is still untouched is restated
     * IN PLACE, and the difference posted on its own event -- increased or reduced -- keyed on a fresh id,
     * so a second change to the same instalment posts again. One already paid in advance keeps its amount
     * and the new one starts after it (plan R4). The schedule is replaced, never mutated (V1's rule): the
     * old one TERMINATED, a new ACTIVE one at the new amount carrying the same next due date and paying end,
     * so the roll-forward raises every later instalment at the new amount.
     *
     * <p>A schedule that is not ACTIVE (suspended, ended) is left alone: nothing is being billed.
     */
    @Transactional
    void restatePremium(UUID tenantId, String policyNumber, BigDecimal amount, LocalDate effectiveFrom, String reason) {
        BillingSchedule active = billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .orElse(null);
        if (active == null) {
            return;
        }
        for (PremiumInvoice invoice : premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)) {
            if (invoice.getDueDate().isBefore(effectiveFrom) || !invoice.isUntouched()) {
                continue;
            }
            BigDecimal delta = amount.subtract(invoice.getAmount());
            if (delta.signum() == 0) {
                continue;
            }
            invoice.restateAmount(amount);
            premiumInvoiceRepository.save(invoice);
            eventPublisher.publishEvent(DomainEventEnvelope.of(
                delta.signum() > 0 ? "billing.PremiumInvoiceIncreased" : "billing.PremiumInvoiceReduced", tenantId,
                Map.of("restatementId", UUID.randomUUID().toString(), "invoiceId", invoice.getInvoiceId(),
                       "policyNumber", policyNumber, "dueDate", invoice.getDueDate().toString(), "reason", reason,
                       "amount", Map.of("amount", delta.abs().toPlainString(), "currencyCode", invoice.getCurrency()))));
        }
        active.terminate();
        billingScheduleRepository.saveAndFlush(active);
        billingScheduleRepository.save(active.restatedAt(amount));
    }

    /**
     * No premium is owed after {@code after} (policy.PremiumsEnded -- a funeral plan's main member died and
     * the family is covered free to the next premium date). The schedule ends, and every unsettled
     * instalment due after that date is waived AND its outstanding receivable reversed, on
     * billing.PremiumInvoiceReduced for the whole outstanding amount. A plain waive would leave the
     * receivable in the ledger: finaccounting has no rule for billing.InvoiceWaived.
     */
    @Transactional
    void endBillingAfter(UUID tenantId, String policyNumber, LocalDate after, String reason) {
        terminateScheduleForExpiry(tenantId, policyNumber);
        for (PremiumInvoice invoice : premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)) {
            if (!invoice.getDueDate().isAfter(after) || !UNSETTLED.contains(invoice.getStatus())) {
                continue;
            }
            BigDecimal outstanding = invoice.balanceDue(creditedAgainst(invoice));
            invoice.waive(reason);
            premiumInvoiceRepository.save(invoice);
            if (outstanding.signum() > 0) {
                eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumInvoiceReduced", tenantId,
                    Map.of("restatementId", UUID.randomUUID().toString(), "invoiceId", invoice.getInvoiceId(),
                           "policyNumber", policyNumber, "dueDate", invoice.getDueDate().toString(), "reason", reason,
                           "amount", Map.of("amount", outstanding.toPlainString(), "currencyCode", invoice.getCurrency()))));
            }
        }
        returnBalanceOnEnd(tenantId, policyNumber);
    }

    /**
     * A deferral with contributions continuing (policy.PremiumPayingTermRestated, D2): the ACTIVE
     * schedule's paying end moves, and the roll-forward drain raises the invoices up to it.
     */
    @Transactional
    void restatePremiumPayingUntil(UUID tenantId, String policyNumber, LocalDate until) {
        billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .ifPresent(schedule -> {
                schedule.restatePremiumPayingUntil(until);
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
        return raiseSinglePremiumInvoice(tenantId, policyNumber, enrolmentSubmissionId, amount, currency, acceptedOn,
            List.of());
    }

    /**
     * @param covers what each borrower's premium pays for ({@code memberRef, coversFrom, coversTo, amount}), as policy
     *     stated it on the accepted file. Carried on the invoice event for the ledger, which earns a PAA premium over
     *     each borrower's own loan (IFRS 17 I3a); billing itself reads nothing from it.
     */
    @Transactional
    UUID raiseSinglePremiumInvoice(UUID tenantId, String policyNumber, UUID enrolmentSubmissionId,
                                    BigDecimal amount, String currency, LocalDate acceptedOn,
                                    List<Map<String, Object>> covers) {
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
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
                   "covers", covers == null ? List.of() : covers)));

        log.info("Raised single-premium invoice {} of {} {} for enrolment submission {} on policy {}",
            invoice.getInvoiceId(), amount.toPlainString(), currency, enrolmentSubmissionId, policyNumber);
        return invoice.getInvoiceId();
    }

    /**
     * The one charge a retail single-premium policy ever raises, due when its cover begins.
     *
     * <p>Separate from {@link #raiseSinglePremiumInvoice} rather than a null submission id on
     * it, because the two answer different questions. That one charges a LENDER for a file of
     * borrowers and dedupes on the file; this charges a CUSTOMER for their own contract and
     * dedupes on the policy. Folding them together would have given the retail path the
     * lender's payment term and the lender's grace reasoning, neither of which applies.
     *
     * <p>The grace window comes from the product, exactly as a scheduled invoice's does: an
     * unpaid retail premium lapses one ordinary policy, which is the situation the product's
     * own grace period was written for. (The enrolment path deliberately does NOT read it —
     * see its comment — because a lender's unpaid file is a collections matter, not a lapse.)
     *
     * <p>Due on the issue date itself, not issue + a payment term. A single premium buys cover
     * that starts now; a due date after commencement would mean the insurer carries risk it has
     * not been paid for and cannot dun anybody for, which is the shape of the gap this method
     * exists to close.
     *
     * @param issueDate the policy's OWN issue date, off the event. Never {@code now()}:
     *     {@code ux_premium_invoice_policy_inception} includes due_date because the table is
     *     partitioned on it, so the guarantee against double-charging a redelivered
     *     {@code policy.PolicyIssued} holds only while a redelivery recomputes the same date.
     */
    @Transactional
    UUID raisePolicyInceptionInvoice(UUID tenantId, String policyNumber, UUID productVersionId,
                                      LocalDate issueDate, BigDecimal amount, String currency) {
        return raisePolicyInceptionInvoice(tenantId, policyNumber, productVersionId, issueDate, amount, currency, null);
    }

    /**
     * @param coverEndsOn the last day the single premium buys cover for (the day before maturity), stated on the
     *     invoice event for the ledger's PAA earning; null where the contract does not term, read as a year
     */
    @Transactional
    UUID raisePolicyInceptionInvoice(UUID tenantId, String policyNumber, UUID productVersionId,
                                      LocalDate issueDate, BigDecimal amount, String currency, LocalDate coverEndsOn) {
        ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(productVersionId);
        LocalDate graceEnd = issueDate.plusDays(snapshot.gracePeriodDays());

        // Asked before inserting, for the reason raiseSinglePremiumInvoice sets out at length:
        // a failed statement poisons the whole Postgres transaction, so nothing can be read
        // back inside a catch block. A policy that already has an inception invoice has been
        // issued once and told to billing twice.
        boolean alreadyRaised = premiumInvoiceRepository
            .findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId).stream()
            .anyMatch(existing -> existing.getBillingScheduleId() == null
                && existing.getEnrolmentSubmissionId() == null);
        if (alreadyRaised) {
            log.info("Policy {} already carries its inception invoice -- redelivered PolicyIssued, "
                + "not charging it again", policyNumber);
            return null;
        }

        LocalDate coverEnd = coverEndsOn != null && !coverEndsOn.isBefore(issueDate) ? coverEndsOn
            : issueDate.plusYears(1).minusDays(1);
        PremiumInvoice invoice = premiumInvoiceRepository.save(
            PremiumInvoice.forPolicyInception(tenantId, policyNumber, issueDate, amount, currency, graceEnd, coverEnd));
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumInvoiceGenerated", tenantId,
            Map.of("invoiceId", invoice.getInvoiceId(), "policyNumber", policyNumber,
                   "dueDate", issueDate.toString(),
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
                   "covers", List.of(cover(issueDate, coverEnd, amount)))));

        log.info("Raised inception invoice {} of {} {} for single-premium policy {}",
            invoice.getInvoiceId(), amount.toPlainString(), currency, policyNumber);
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
                                String exitReason, LocalDate exitDate, String lenderName) {
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

        // What the lender still owed on the file before this credit. The credit lowers that first; whatever is left the
        // lender had already paid, and goes back to it by bank transfer (2026-10-30, the user: pay it back). A credit
        // raised after the file was paid in full used to be recorded and never settled.
        BigDecimal paid = invoice.get().getAmountPaid() == null ? BigDecimal.ZERO : invoice.get().getAmountPaid();
        BigDecimal stillOwed = invoice.get().getAmount().subtract(paid).subtract(creditedAgainst(invoice.get()))
            .max(BigDecimal.ZERO);
        BigDecimal refund = amount.subtract(stillOwed).max(BigDecimal.ZERO);

        PremiumCredit credit = new PremiumCredit(tenantId,
            policyNumber, policyMemberId, invoice.get().getInvoiceId(), amount, currency,
            exitReason, exitDate);
        String payee = (lenderName == null || lenderName.isBlank() ? "The lender" : lenderName)
            + " — policyholder of " + policyNumber;
        if (refund.signum() > 0) {
            credit.requestRefund(refund, payee);
        }
        credit = premiumCreditRepository.save(credit);
        premiumCreditRepository.flush();
        // Money movement step 4 (2026-11-04): the refund waits for a second person (approveLenderRefund), who releases it
        // to the bank-transfer queue.

        // If payments and credits now cover the invoice, nothing more is owed -- otherwise a
        // file whose every borrower left sits DUE and the arrears sweep chases it for nothing.
        invoice.get().settleIfCovered(creditedAgainst(invoice.get()));
        premiumInvoiceRepository.save(invoice.get());

        // The ONLY input to the commission clawback. A refund and its clawback must not be
        // separable: without the matching reversal the insurer returns the premium while the
        // bank keeps commission on money that was given back -- a loss on every early
        // settlement, on a product whose settlement volume the bank controls.
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumRefundDue", tenantId,
            Map.of("creditId", credit.getCreditId(),
                   "policyNumber", policyNumber,
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

    /**
     * Raise the next batch of invoices for one schedule whose pre-created ones are running low.
     * Called by {@code InvoiceRollForward} for each schedule {@code schedules_due_for_invoicing()}
     * returns.
     *
     * <p>Takes a write lock on the row first, which is the exactly-once guarantee: two drain
     * instances both selected this schedule, but the second blocks here until the first commits,
     * then re-reads a {@code nextDueDate} already advanced past the horizon and generates nothing.
     * A schedule no longer ACTIVE (terminated by expiry or a claim between selection and this call)
     * is left alone.
     *
     * <p>The product version comes from the policy this schedule bills -- billing already depends
     * on {@code policy::api} -- because the schedule row does not carry it and
     * {@code generateInvoicesAhead} needs the grace-period days.
     */
    @Transactional
    public void rollForward(UUID scheduleId) {
        BillingSchedule schedule = billingScheduleRepository.findByIdForUpdate(scheduleId).orElse(null);
        if (schedule == null || !"ACTIVE".equals(schedule.getStatus()) || schedule.getNextDueDate() == null) {
            return;
        }
        // Already reached the end of the contract between selection and now: nothing to raise.
        if (!schedule.dueWithinPayingTerm(schedule.getNextDueDate())) {
            return;
        }
        UUID productVersionId = policyApi.getPolicy(schedule.getPolicyNumber()).productVersionId();
        // fromDate = the schedule's own next due date, so the twelve-month horizon in
        // generateInvoicesAhead is measured forward from where invoicing currently stands rather
        // than from a fixed issue date -- each roll adds another year (bounded by the paying end).
        generateInvoicesAhead(schedule.getTenantId(), schedule, productVersionId, schedule.getNextDueDate());
    }

    /**
     * The furthest due date to which premiums are paid without a gap, from the earliest invoice.
     * PAID and WAIVED both count -- a waived instalment does not break cover -- and the run stops at
     * the first invoice that is neither. Null when even the first invoice is unpaid.
     *
     * <p>A cash-value policy's worth turns on completed premium years, so what matters is not "was
     * this one paid" but "paid up to when, unbroken". Only billing can answer that, because only
     * billing holds the invoices.
     */
    private LocalDate contiguousPaidToDate(String policyNumber, UUID tenantId) {
        LocalDate paidTo = null;
        for (PremiumInvoice inv : premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)) {
            if ("PAID".equals(inv.getStatus()) || "WAIVED".equals(inv.getStatus())) {
                // Paid to the day after the cover it bought. In arrears that is its due date; in advance, the start
                // of the next period -- the recorded cover says which without knowing the convention.
                paidTo = inv.getCoversTo() != null ? inv.getCoversTo().plusDays(1) : inv.getDueDate();
            } else {
                break;
            }
        }
        return paidTo;
    }

    private void generateInvoicesAhead(UUID tenantId, BillingSchedule schedule, UUID productVersionId, LocalDate fromDate) {
        ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(productVersionId);
        LocalDate cursor = schedule.getNextDueDate();
        // Stop at the twelve-month horizon OR the end of the contract, whichever comes first. The
        // horizon bounds how far ahead invoices are pre-created (the roll-forward drain extends
        // them later); the paying end bounds the contract itself, so a 6-month policy gets six
        // invoices and a limited-pay policy is not billed past its paying term. A null paying end
        // means the contract does not term -- whole life, an annually renewable scheme -- and only
        // the horizon applies. The bound is INCLUSIVE: billing's first invoice falls one period
        // after issue (premium in arrears), so a 12-month monthly policy is due issue+1..issue+12,
        // and excluding the endpoint would drop the final month.
        // In advance (billing V11) the last instalment falls due one period BEFORE the paying end -- see
        // BillingSchedule.dueWithinPayingTerm; in arrears it may fall on it.
        LocalDate horizon = fromDate.plusMonths(SCHEDULE_HORIZON_MONTHS);
        List<PremiumInvoice> toCreate = new ArrayList<>();
        // Twelve months of instalments: in advance the first falls on fromDate itself, so the horizon is exclusive
        // (issue..issue+11 for a monthly whole-life policy); in arrears it fell a period later, so inclusive.
        while ((schedule.isBilledInAdvance() ? cursor.isBefore(horizon) : !cursor.isAfter(horizon))
                && schedule.dueWithinPayingTerm(cursor)) {
            LocalDate graceEnd = cursor.plusDays(snapshot.gracePeriodDays());
            LocalDate next = schedule.periodAfter(cursor);
            // The period it pays for, stored on it: from its due date in advance, ending the day before in arrears.
            LocalDate coversFrom = schedule.isBilledInAdvance() ? cursor
                : schedule.periodBefore(cursor);
            LocalDate coversTo = schedule.isBilledInAdvance() ? next.minusDays(1) : cursor.minusDays(1);
            toCreate.add(new PremiumInvoice(tenantId, schedule.getBillingScheduleId(), schedule.getPolicyNumber(),
                cursor, schedule.getPremiumAmount(), schedule.getPremiumCurrency(), graceEnd, coversFrom, coversTo));
            cursor = next;
        }
        premiumInvoiceRepository.saveAll(toCreate);
        schedule.advanceNextDueDate(cursor);
        billingScheduleRepository.save(schedule);
        for (PremiumInvoice invoice : toCreate) {
            // The cover it pays for, as recorded on it -- what the ledger's PAA earning reads (IFRS 17 I3a).
            LocalDate due = invoice.getDueDate();
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumInvoiceGenerated", tenantId,
                Map.of("invoiceId", invoice.getInvoiceId(), "policyNumber", invoice.getPolicyNumber(), "dueDate", due.toString(),
                       "amount", Map.of("amount", invoice.getAmount().toPlainString(), "currencyCode", invoice.getCurrency()),
                       "covers", List.of(cover(invoice.getCoversFrom(), invoice.getCoversTo(), invoice.getAmount())))));
        }
    }

    /** One cover an invoice pays for, as the ledger's PAA earning reads it (IFRS 17 I3a). */
    private static Map<String, Object> cover(LocalDate from, LocalDate to, BigDecimal amount) {
        return Map.of("memberRef", "", "coversFrom", from.toString(), "coversTo", to.toString(),
            "amount", amount.toPlainString());
    }

    /** Single-invoice reads, which resolve their own dunning level -- one lookup for one row. */
    private InvoiceView toView(PremiumInvoice invoice) {
        Integer dunningLevel = arrearsCaseRepository.findByInvoiceIdAndTenantIdAndResolvedAtIsNull(invoice.getInvoiceId(), invoice.getTenantId())
            .map(ArrearsCase::getDunningLevel).orElse(null);
        return toView(invoice, dunningLevel, creditedAgainst(invoice));
    }

    /**
     * List reads, which resolve every dunning level in one query and pass it in.
     *
     * <p>Null means no OPEN arrears case for this invoice, which is not the same as level 0 --
     * an invoice that was never overdue and one whose arrears were resolved both land here, and
     * neither should render a dunning badge.
     */
    private InvoiceView toView(PremiumInvoice invoice, Integer dunningLevel, BigDecimal credited) {
        return new InvoiceView(invoice.getInvoiceId(), invoice.getPolicyNumber(), invoice.getDueDate(),
            invoice.getAmount(), invoice.getCurrency(), InvoiceStatus.valueOf(invoice.getStatus()),
            invoice.getGracePeriodEndsAt(), dunningLevel,
            (invoice.getAmountPaid() == null ? BigDecimal.ZERO : invoice.getAmountPaid()).setScale(2),
            credited.setScale(2), invoice.balanceDue(credited), invoice.getEnrolmentSubmissionId(),
            invoice.getCoversFrom(), invoice.getCoversTo());
    }

    /** Everything credited back off one invoice. Zero when nothing was. */
    /** payment's purpose for a lender's premium paid back (payment V20). */
    static final String CREDIT_REFUND_PURPOSE = "CREDIT_LIFE_PREMIUM_REFUND";

    /**
     * The transfer back to the lender was made (2026-10-30): the credit is settled, and the ledger is told the money
     * left the bank -- on the real transition only, so a redelivery posts nothing twice.
     */
    @Transactional
    public void markCreditRefunded(UUID creditId, UUID disbursementId) {
        premiumCreditRepository.findById(creditId).ifPresent(credit -> {
            if (!credit.refundPaid(disbursementId)) {
                return;
            }
            premiumCreditRepository.save(credit);
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumRefundPaid", credit.getTenantId(),
                Map.of("creditId", credit.getCreditId().toString(),
                       "policyNumber", credit.getPolicyNumber(),
                       "amount", Map.of("amount", credit.getRefundAmount().toPlainString(),
                                        "currencyCode", credit.getCurrency()))));
            log.info("Paid {} {} back to the lender of {} for credit {}", credit.getRefundAmount(),
                credit.getCurrency(), credit.getPolicyNumber(), creditId);
        });
    }

    @Override
    @Transactional
    public LenderRefundView approveLenderRefund(UUID creditId, String approvedBy) {
        UUID tenantId = TenantContext.get();
        PremiumCredit credit = lenderRefundOrThrow(tenantId, creditId);
        credit.approveRefund(approvedBy);
        premiumCreditRepository.save(credit);
        // A bank transfer finance makes by hand, as a credit-life claim's is: the insurer deals only with the lender.
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("creditId", credit.getCreditId().toString());
        request.put("idempotencyKey", "credit-refund:" + credit.getCreditId());
        request.put("policyNumber", credit.getPolicyNumber());
        request.put("payeeRef", credit.getRefundPayee());
        request.put("disbursementMethod", "EFT");
        request.put("amount", Map.of("amount", credit.getRefundAmount().toPlainString(), "currencyCode", credit.getCurrency()));
        eventPublisher.publishEvent(DomainEventEnvelope.of("billing.PremiumRefundPayoutRequested", tenantId, request));
        return lenderRefundView(credit);
    }

    @Override
    @Transactional
    public LenderRefundView refuseLenderRefund(UUID creditId, String refusedBy, String reason) {
        PremiumCredit credit = lenderRefundOrThrow(TenantContext.get(), creditId);
        try {
            credit.refuseRefund(refusedBy, reason);
        } catch (IllegalArgumentException e) {
            throw new BalanceRefusedException(e.getMessage());
        }
        premiumCreditRepository.save(credit);
        log.warn("Refund of {} {} to the lender of {} refused by {}: {}", credit.getRefundAmount(), credit.getCurrency(),
            credit.getPolicyNumber(), refusedBy, credit.getRefundRefusalReason());
        return lenderRefundView(credit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LenderRefundView> listLenderRefundsAwaitingApproval() {
        return premiumCreditRepository.findByTenantIdAndRefundStatusOrderByCreatedAt(TenantContext.get(), "AWAITING_APPROVAL")
            .stream().map(BillingApiImpl::lenderRefundView).toList();
    }

    private PremiumCredit lenderRefundOrThrow(UUID tenantId, UUID creditId) {
        PremiumCredit credit = premiumCreditRepository.findById(creditId)
            .filter(c -> c.getTenantId().equals(tenantId) && c.getRefundStatus() != null)
            .orElseThrow(() -> new BalanceRefusedException("No lender refund " + creditId + " on this tenant's books"));
        if (!"AWAITING_APPROVAL".equals(credit.getRefundStatus())) {
            throw new BalanceRefusedException("This lender refund is not waiting for approval (it is "
                + credit.getRefundStatus() + ")");
        }
        return credit;
    }

    private static LenderRefundView lenderRefundView(PremiumCredit c) {
        return new LenderRefundView(c.getCreditId(), c.getPolicyNumber(), c.getRefundPayee(), c.getRefundAmount(),
            c.getCurrency(), c.getRefundStatus(), c.getExitReason(), c.getCreatedAt(), c.getRefundDecidedBy(),
            c.getRefundRefusalReason());
    }

    /** The rail refused the transfer. Left visible as FAILED rather than retried behind anybody's back. */
    @Transactional
    public void markCreditRefundFailed(UUID creditId) {
        premiumCreditRepository.findById(creditId).ifPresent(credit -> {
            credit.refundFailed();
            premiumCreditRepository.save(credit);
            log.warn("The transfer paying credit {} back to the lender of {} failed", creditId, credit.getPolicyNumber());
        });
    }

    private BigDecimal creditedAgainst(PremiumInvoice invoice) {
        return premiumCreditRepository
            .findByTenantIdAndOriginalInvoiceId(invoice.getTenantId(), invoice.getInvoiceId()).stream()
            .map(PremiumCredit::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PremiumCreditView> listCredits(String policyNumber) {
        return premiumCreditRepository.findByTenantIdAndPolicyNumber(TenantContext.get(), policyNumber).stream()
            .sorted(java.util.Comparator.comparing(PremiumCredit::getCreatedAt))
            .map(c -> new PremiumCreditView(c.getCreditId(), c.getPolicyNumber(), c.getPolicyMemberId(),
                c.getOriginalInvoiceId(), c.getAmount(), c.getCurrency(), c.getExitReason(), c.getExitDate(),
                c.getCreatedAt(), c.getRefundAmount(), c.getRefundStatus(), c.getRefundPayee(), c.getRefundRefusalReason()))
            .toList();
    }
}
