package tz.co.nlolo.lifeplatform.billing.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.*;
import tz.co.nlolo.lifeplatform.billing.domain.ArrearsCase;
import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.infrastructure.ArrearsCaseRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BillingScheduleRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.FieldReceiptRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingApiImpl implements BillingApi {

    // Schedule generation pre-creates invoices this far ahead of need, mirroring the platform's
    // established convention of pre-creating partitions/reservations ahead of when they're
    // required rather than generating them lazily at due-date time (docs/06's partition-headroom
    // rationale; Module-Architecture-B1's TTL-sweep self-healing framing). 12 months covers a
    // full ANNUALLY cycle and 12x/4x a MONTHLY/QUARTERLY one.
    private static final int SCHEDULE_HORIZON_MONTHS = 12;

    private final BillingScheduleRepository billingScheduleRepository;
    private final PremiumInvoiceRepository premiumInvoiceRepository;
    private final ArrearsCaseRepository arrearsCaseRepository;
    private final FieldReceiptRepository fieldReceiptRepository;
    private final ProductApi productApi;
    private final ApplicationEventPublisher eventPublisher;

    public BillingApiImpl(BillingScheduleRepository billingScheduleRepository, PremiumInvoiceRepository premiumInvoiceRepository,
                           ArrearsCaseRepository arrearsCaseRepository, FieldReceiptRepository fieldReceiptRepository,
                           ProductApi productApi, ApplicationEventPublisher eventPublisher) {
        this.billingScheduleRepository = billingScheduleRepository;
        this.premiumInvoiceRepository = premiumInvoiceRepository;
        this.arrearsCaseRepository = arrearsCaseRepository;
        this.fieldReceiptRepository = fieldReceiptRepository;
        this.productApi = productApi;
        this.eventPublisher = eventPublisher;
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
        List<PremiumInvoice> invoices = status == null
            ? premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)
            : premiumInvoiceRepository.findByPolicyNumberAndTenantIdAndStatusIn(policyNumber, tenantId, List.of(status.name()));
        return invoices.stream().map(this::toView).toList();
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
    public FieldReceiptResult captureFieldReceipt(UUID agentId, String policyNumber, BigDecimal amount, String currency,
                                                   String clientIdempotencyKey, Instant capturedAtClient) {
        throw new UnsupportedOperationException("Implemented in Task 5");
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
        // Per db-migrations/billing/V1's own column comment: never mutated in place -- the old
        // ACTIVE schedule is TERMINATED and a new one created ACTIVE, so schedule history is a
        // sequence of rows, not edits. This mirrors policyloan's own append-only-ledger posture.
        billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .ifPresent(old -> {
                old.terminate();
                billingScheduleRepository.save(old);
            });
        // An endorsement in this plan's scope never changes premiumAmount/premiumFrequency
        // (no endorsement type in policy.PolicyApi.EndorsementInput carries either field as of
        // M3) -- this method exists so a future endorsement type CAN regenerate the schedule
        // without billing needing further changes, but for M4 the only real caller
        // (PolicyEventListener.onPolicyEndorsed) is a documented no-op scope limitation.
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
            default -> throw new IllegalArgumentException("Unknown premium frequency: " + frequency);
        };
        return from.plus(step);
    }

    private InvoiceView toView(PremiumInvoice invoice) {
        Integer dunningLevel = arrearsCaseRepository.findByInvoiceIdAndTenantIdAndResolvedAtIsNull(invoice.getInvoiceId(), invoice.getTenantId())
            .map(ArrearsCase::getDunningLevel).orElse(null);
        return new InvoiceView(invoice.getInvoiceId(), invoice.getPolicyNumber(), invoice.getDueDate(),
            invoice.getAmount(), invoice.getCurrency(), InvoiceStatus.valueOf(invoice.getStatus()),
            invoice.getGracePeriodEndsAt(), dunningLevel);
    }
}
