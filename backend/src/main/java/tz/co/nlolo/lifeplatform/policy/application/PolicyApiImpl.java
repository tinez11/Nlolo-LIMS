package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.*;
import tz.co.nlolo.lifeplatform.policy.infrastructure.*;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyApiImpl implements PolicyApi {

    private static final Logger log = LoggerFactory.getLogger(PolicyApiImpl.class);

    private final PolicyRepository policyRepository;
    private final PolicyAccountRepository policyAccountRepository;
    private final EndorsementRepository endorsementRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final CoverageRepository coverageRepository;
    private final LoanValueReservationRepository loanValueReservationRepository;
    private final PartyApi partyApi;
    private final ProductApi productApi;
    private final ReferenceDataApi referenceDataApi;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public PolicyApiImpl(PolicyRepository policyRepository, PolicyAccountRepository policyAccountRepository,
                          EndorsementRepository endorsementRepository, BeneficiaryRepository beneficiaryRepository,
                          CoverageRepository coverageRepository, LoanValueReservationRepository loanValueReservationRepository,
                          PartyApi partyApi, ProductApi productApi, ReferenceDataApi referenceDataApi,
                          ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.policyAccountRepository = policyAccountRepository;
        this.endorsementRepository = endorsementRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.coverageRepository = coverageRepository;
        this.loanValueReservationRepository = loanValueReservationRepository;
        this.partyApi = partyApi;
        this.productApi = productApi;
        this.referenceDataApi = referenceDataApi;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy) {
        UUID tenantId = TenantContext.get();
        partyApi.getParty(request.policyholderPartyId()); // existence check -- PartyNotFoundException propagates as-is
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(request.productId(), LocalDate.now());

        // Placeholder generation scheme (flagged): policy.policy's own column comment describes
        // a "tenant/product/year/sequence, human-meaningful for USSD/call-center lookup"
        // business key -- no sequence generator or product-code lookup is wired here. This is
        // pattern-valid (^[A-Z0-9-]{6,20}$) and unique enough for M3; a later milestone can
        // replace the generation strategy without changing this method's signature.
        String policyNumber = "POL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<Beneficiary> beneficiaries = validateAndBuildBeneficiaries(tenantId, policyNumber, request.beneficiaries());

        Policy policy = new Policy(policyNumber, tenantId, request.policyholderPartyId(), request.productId(), request.productVersionId(),
            snapshot.category().name(), request.agentOfRecordId(), request.sumAssuredAmount(), request.sumAssuredCurrency(),
            request.premiumAmount(), request.premiumCurrency(), request.premiumFrequency(), underwritingCaseId, issuedBy);
        policy.activate(LocalDate.now());
        policyRepository.save(policy);

        policyAccountRepository.save(new PolicyAccount(policyNumber, tenantId, BigDecimal.ZERO, request.sumAssuredCurrency()));

        // Only a DEATH coverage row is created at issuance -- ProductApi does not expose the
        // full benefit schedule list back to callers (publishVersion accepts one at authoring
        // time, but no getter returns it), so a Coverage row per BenefitScheduleEntry isn't
        // buildable without a further ProductApi change this plan does not make. Flagged.
        coverageRepository.save(new Coverage(tenantId, policyNumber, BenefitType.DEATH.name(), request.sumAssuredAmount(), request.sumAssuredCurrency()));

        beneficiaryRepository.saveAll(beneficiaries);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", request.policyholderPartyId());
        payload.put("productId", request.productId());
        payload.put("productVersionId", request.productVersionId());
        payload.put("sumAssured", Map.of("amount", request.sumAssuredAmount().toPlainString(), "currencyCode", request.sumAssuredCurrency()));
        payload.put("issueDate", policy.getIssueDate().toString());
        payload.put("premium", Map.of("amount", request.premiumAmount().toPlainString(), "currencyCode", request.premiumCurrency()));
        payload.put("premiumFrequency", request.premiumFrequency());
        payload.put("agentOfRecordId", request.agentOfRecordId()); // nullable -- see Global Constraints
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyIssued", tenantId, payload));

        return toView(policy);
    }

    @Override
    @Transactional
    public PolicyView applyEndorsement(String policyNumber, EndorsementInput request, String appliedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        if (!policy.isInForce()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be in force to apply an endorsement (current: " + policy.getStatus() + ")");
        }
        Endorsement endorsement = new Endorsement(tenantId, policyNumber, request.endorsementType(), request.effectiveDate(), request.changes(), appliedBy);
        endorsementRepository.save(endorsement);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyEndorsed", tenantId,
            Map.of("policyNumber", policyNumber, "endorsementType", request.endorsementType(), "effectiveDate", request.effectiveDate().toString())));
        return toView(policy);
    }

    @Override
    @Transactional
    public void replaceBeneficiaries(String policyNumber, List<BeneficiaryInput> beneficiaries, String changedBy) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        List<Beneficiary> newBeneficiaries = validateAndBuildBeneficiaries(tenantId, policyNumber, beneficiaries);

        List<Beneficiary> existing = beneficiaryRepository.findByPolicyNumberAndActiveTrue(policyNumber);
        existing.forEach(Beneficiary::deactivate);
        beneficiaryRepository.saveAll(existing);
        beneficiaryRepository.saveAll(newBeneficiaries);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.BeneficiaryChanged", tenantId,
            Map.of("policyNumber", policyNumber, "changedAt", Instant.now().toString())));
    }

    @Override
    public SurrenderQuoteView quoteSurrenderValue(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.findById(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(policy.getProductId(), LocalDate.now());

        BigDecimal chargePercent = resolveSurrenderChargePercent(snapshot.surrenderChargeScheduleJson(), policy.getIssueDate());
        BigDecimal charge = account.getCashValueAmount().multiply(chargePercent).divide(new BigDecimal("100"));
        BigDecimal quotedValue = account.getCashValueAmount().subtract(charge);
        Instant quotedAt = Instant.now();

        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.SurrenderValueCalculated", tenantId,
            Map.of("policyNumber", policyNumber,
                   "quotedValue", Map.of("amount", quotedValue.toPlainString(), "currencyCode", account.getCashValueCurrency()),
                   "quotedAt", quotedAt.toString())));

        return new SurrenderQuoteView(policyNumber, quotedValue, account.getCashValueCurrency(), quotedAt);
    }

    /**
     * Duration-band -> charge% shape (Global Constraints -- a plan-level decision pending
     * Actuarial confirmation, not a confirmed contractual schedule): a JSON array of
     * {"minMonths": int, "maxMonths": int-or-absent, "chargePercent": number} objects,
     * minMonths inclusive, maxMonths exclusive (absent/null = unbounded). A missing, blank, or
     * unparseable schedule means ZERO charge -- this must never throw out to the caller.
     *
     * <p>Package-private (not private) solely so PolicyApiImplSurrenderChargeTest can exercise
     * this money-affecting band-parsing/percentage logic directly, without a Spring context --
     * mirroring underwriting.SimpleRulesEngineTest's approach to unit-testing pure decision logic.
     */
    BigDecimal resolveSurrenderChargePercent(String scheduleJson, LocalDate issueDate) {
        if (scheduleJson == null || scheduleJson.isBlank() || issueDate == null) {
            return BigDecimal.ZERO;
        }
        try {
            long monthsInForce = Period.between(issueDate, LocalDate.now()).toTotalMonths();
            JsonNode bands = objectMapper.readTree(scheduleJson);
            for (JsonNode band : bands) {
                long minMonths = band.path("minMonths").asLong(0);
                long maxMonths = band.hasNonNull("maxMonths") ? band.path("maxMonths").asLong() : Long.MAX_VALUE;
                if (monthsInForce >= minMonths && monthsInForce < maxMonths) {
                    return new BigDecimal(band.path("chargePercent").asText("0"));
                }
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("Unparseable surrender_charge_schedule for a policy issued {} -- treating as zero charge", issueDate, e);
            return BigDecimal.ZERO;
        }
    }

    @Override
    public PolicyView getPolicy(String policyNumber) {
        return toView(findPolicyOrThrow(policyNumber, TenantContext.get()));
    }

    @Override
    public Page<PolicyView> searchPolicies(UUID policyholderPartyId, PolicyStatus status, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<Policy> page;
        if (policyholderPartyId != null && status != null) {
            page = policyRepository.findByTenantIdAndPolicyholderPartyIdAndStatus(tenantId, policyholderPartyId, status.name(), pageable);
        } else if (policyholderPartyId != null) {
            page = policyRepository.findByTenantIdAndPolicyholderPartyId(tenantId, policyholderPartyId, pageable);
        } else if (status != null) {
            page = policyRepository.findByTenantIdAndStatus(tenantId, status.name(), pageable);
        } else {
            page = policyRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toView);
    }

    @Override
    public CoverageStatusView getCoverageStatus(String policyNumber, LocalDate asOf) {
        UUID tenantId = TenantContext.get();
        findPolicyOrThrow(policyNumber, tenantId);
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
        List<CoverageStatusView.ActiveCoverageView> coverages = coverageRepository.findByPolicyNumberAndActiveTrue(policyNumber).stream()
            .filter(c -> !"SURRENDER".equals(c.getBenefitType())) // openapi-policy.yaml's CoverageStatusView enum excludes SURRENDER
            .map(c -> new CoverageStatusView.ActiveCoverageView(BenefitType.valueOf(c.getBenefitType()), c.getSumAssuredAmount(), c.getSumAssuredCurrency()))
            .toList();
        return new CoverageStatusView(policyNumber, effectiveAsOf, coverages);
    }

    @Override
    public boolean isPolicyInForce(String policyNumber, LocalDate asOf) {
        // asOf is accepted (matches the OpenAPI query param and Po3's signature) but not
        // otherwise consulted -- this is a pure "is this policy currently ACTIVE-or-REINSTATED"
        // status read, not a date-bounded coverage-window computation (that's
        // getCoverageStatus's job, which separately filters `active` Coverage rows). Flagged.
        return findPolicyOrThrow(policyNumber, TenantContext.get()).isInForce();
    }

    @Override
    @Transactional
    public UUID reserveLoanValue(String policyNumber, BigDecimal amount, String currency, Duration ttl) {
        // M3 final review, I3. Task 7's Critical (a negative requestedAmount reaching
        // PolicyAccount.increaseEncumbrance, DECREASING loan_encumbrance_amount and thereby
        // RAISING the customer's own available loan value) was closed only at the HTTP
        // boundary, by policyloan.infrastructure.MoneyDto's @DecimalMin("0.01"). This guard
        // closes it at the module boundary instead. PolicyApi is a published @NamedInterface
        // that policyloan already calls and that M5's payment integration is documented to
        // call next; the check below at :~263 is an UPPER bound only
        // (amount.compareTo(available) > 0), which a negative amount passes trivially, so
        // without this line the next non-HTTP caller reintroduces the exploit with no
        // annotation anywhere to protect it. IllegalArgumentException maps to 400
        // VALIDATION_ERROR via GlobalExceptionHandler.handleValidation.
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Reservation amount must be a positive value, was: " + amount);
        }
        UUID tenantId = TenantContext.get();
        // Opportunistic TTL sweep (Module-Architecture-B1; Global Constraints -- a true cross-
        // tenant @Scheduled sweep is architecturally incompatible with this platform's fail-closed
        // RLS design, since a background thread with no TenantContext sees zero rows on every
        // RLS-protected table and there is no tenant-directory table to iterate). Runs inside the
        // CALLER's own TenantContext, so it is RLS-safe and expires only this tenant's stale
        // RESERVED rows for this policy -- self-healing the crash case (reserved, then crashed
        // before confirm/release) on the next real access instead of on a fixed wall-clock timer.
        loanValueReservationRepository.expireStaleReservations(policyNumber, tenantId, Instant.now());
        findPolicyOrThrow(policyNumber, tenantId);
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));

        // Uses the shared native aggregate query (added at the end of Task 1) rather than
        // pulling every RESERVED row into memory and reducing client-side -- same result,
        // one fewer place computing "sum of currently-RESERVED amounts" for this policy.
        BigDecimal currentlyReserved = loanValueReservationRepository.sumReservedAmountForPolicy(policyNumber, tenantId);
        BigDecimal available = account.availableLoanValue(currentlyReserved);
        if (amount.compareTo(available) > 0) {
            throw new InsufficientLoanValueException(
                "Requested " + amount + " " + currency + " exceeds available loan value " + available + " for policy " + policyNumber);
        }

        LoanValueReservation reservation = new LoanValueReservation(tenantId, policyNumber, amount, currency, Instant.now().plus(ttl));
        loanValueReservationRepository.save(reservation);
        return reservation.getReservationId();
    }

    @Override
    @Transactional
    public void confirmReservation(UUID reservationId) {
        UUID tenantId = TenantContext.get();
        // Task 3 review fix (Critical finding #1): PESSIMISTIC_WRITE on the reservation row
        // itself, taken BEFORE the status check and BEFORE the policy_account lock below --
        // closes the race where a concurrent opportunistic TTL sweep (reserveLoanValue)
        // flips this same row RESERVED -> EXPIRED between an unlocked read and this method's
        // write. Whichever transaction (this one, or the sweep's status-conditioned UPDATE)
        // gets here first wins and commits; the loser re-evaluates against the now-committed
        // state (the sweep's WHERE status='RESERVED' no longer matches a row this method just
        // confirmed; this method's status check below sees EXPIRED if the sweep won) instead of
        // blindly overwriting it. Lock ordering (reservation row, then policy_account) is
        // unchanged from before this fix and matches reserveLoanValue's sweep-then-account-lock
        // order, so this does not introduce a new deadlock class.
        LoanValueReservation reservation = loanValueReservationRepository.lockByReservationIdAndTenantId(reservationId, tenantId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!"RESERVED".equals(reservation.getStatus())) {
            // Explicitly rejected, not silently resurrected or made idempotent: a reservation
            // already CONFIRMED, RELEASED, or EXPIRED is a terminal state (see LoanValueReservation's
            // class Javadoc) and a second confirm attempt (e.g. a duplicate policyloan callback)
            // must surface as a clear domain error rather than double-applying the encumbrance
            // increase below.
            throw new InvalidPolicyStateException("Reservation " + reservationId + " is " + reservation.getStatus() + ", not RESERVED -- cannot confirm");
        }
        // Encumbrance updated synchronously here, not via async LoanOriginated consumption --
        // see Global Constraints.
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(reservation.getPolicyNumber())
            .orElseThrow(() -> new PolicyNotFoundException(reservation.getPolicyNumber()));
        account.increaseEncumbrance(reservation.getAmount());
        policyAccountRepository.save(account);

        reservation.confirm();
        loanValueReservationRepository.save(reservation);
    }

    @Override
    @Transactional
    public void releaseEncumbrance(String policyNumber, BigDecimal amount, String currency) {
        // M4's ledger note ("loan_encumbrance_amount only ever increases in M3 -- no
        // repayment-side consumption path") is now partially closed here, for the FAILURE
        // direction only: a disbursement that never actually paid out must not permanently
        // consume the policyholder's loan value. Full repayment-side consumption (decrementing
        // encumbrance as the loan is repaid) remains out of scope for M5.
        UUID tenantId = TenantContext.get();
        // Same lockByPolicyNumber PESSIMISTIC_WRITE discipline confirmReservation uses -- the
        // caller (policyloan.PolicyLoanApiImpl.markDisbursementFailed) already announces its own
        // state change, so nothing is published from here.
        PolicyAccount account = policyAccountRepository.lockByPolicyNumber(policyNumber)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        account.decreaseEncumbrance(amount);
        policyAccountRepository.save(account);
    }

    @Override
    @Transactional
    public void releaseReservation(UUID reservationId) {
        UUID tenantId = TenantContext.get();
        // Same PESSIMISTIC_WRITE fix as confirmReservation above, for the identical race against
        // the TTL sweep.
        LoanValueReservation reservation = loanValueReservationRepository.lockByReservationIdAndTenantId(reservationId, tenantId)
            .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if ("CONFIRMED".equals(reservation.getStatus())) {
            throw new InvalidPolicyStateException("Reservation " + reservationId + " is already CONFIRMED -- cannot release a confirmed reservation");
        }
        if ("RESERVED".equals(reservation.getStatus())) {
            reservation.release();
            loanValueReservationRepository.save(reservation);
        }
        // Already RELEASED or EXPIRED -- idempotent no-op (unlike confirmReservation's explicit
        // rejection above), so a caller retrying after a network timeout on a first,
        // actually-successful release -- or racing the TTL sweep to the same terminal outcome --
        // doesn't get a spurious error.
    }

    @Override
    @Transactional
    public void suspendPolicy(String policyNumber, String reason, String suspendedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Deliverable 3 Rev 2 §3's flagged, unresolved "which product categories support
        // SUSPENDED" item, resolved here as a configurable refdata code set (Task 6's
        // db-migrations/refdata/V2) rather than a hardcoded category list.
        List<ReferenceCodeView> eligibleCategories = referenceDataApi.getCodes("POLICY_SUSPENSION_ELIGIBLE_CATEGORIES");
        boolean eligible = eligibleCategories.stream().anyMatch(c -> c.code().equals(policy.getProductCategory()));
        if (!eligible) {
            throw new InvalidPolicyStateException("Product category " + policy.getProductCategory() + " is not eligible for SUSPENDED status");
        }
        policy.suspend(reason);
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicySuspended", tenantId,
            Map.of("policyNumber", policyNumber, "suspendedAt", policy.getSuspendedAt().toString(), "reason", reason)));
    }

    @Override
    @Transactional
    public void resumeSuspendedPolicy(String policyNumber, String resumedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        policy.resume();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyResumed", tenantId,
            Map.of("policyNumber", policyNumber, "resumedAt", Instant.now().toString())));
    }

    @Override
    @Transactional
    public void lapsePolicy(String policyNumber, String lapsedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        policy.lapse();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyLapsed", tenantId,
            Map.of("policyNumber", policyNumber, "lapsedAt", policy.getLapsedAt().toString())));
    }

    @Override
    @Transactional
    public void reinstatePolicy(String policyNumber, String reinstatedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Real bug, found by PolicyContractTest's own negative control: getLapsedAt() is null on
        // any non-LAPSED policy, so the window check below must never run before this guard --
        // it used to, and reinstating a never-lapsed (e.g. ACTIVE) policy NPE'd into a bare 500
        // instead of the 409 INVALID_POLICY_STATE Policy.reinstate()'s own guard would have given.
        if (!"LAPSED".equals(policy.getStatus())) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be LAPSED to be REINSTATED (current: " + policy.getStatus() + ")");
        }
        int windowMonths = Integer.parseInt(referenceDataApi.getValue("TZ_REINSTATEMENT_WINDOW_MONTHS", "TZ"));
        long monthsSinceLapse = Period.between(policy.getLapsedAt().atZone(ZoneOffset.UTC).toLocalDate(), LocalDate.now()).toTotalMonths();
        if (monthsSinceLapse > windowMonths) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " lapsed " + monthsSinceLapse
                + " months ago, exceeding the " + windowMonths + "-month reinstatement window (TZ_REINSTATEMENT_WINDOW_MONTHS, a PLACEHOLDER pending B1 sign-off)");
        }
        policy.reinstate();
        policyRepository.save(policy);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyReinstated", tenantId,
            Map.of("policyNumber", policyNumber, "reinstatedAt", Instant.now().toString())));
    }

    @Override
    @Transactional
    public void markMatured(String policyNumber, String maturedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Either terminal status, not just MATURED (M6 final-review C1, part 2): Policy.mature()
        // now treats an already-SURRENDERED policy as a satisfied post-condition and leaves the
        // status alone, so publishing PolicyMatured for it would announce a transition that did not
        // happen. "Already closed" is the condition that suppresses the event, exactly as "already
        // in MY target status" did before the guards were widened.
        boolean alreadyClosed = "MATURED".equals(policy.getStatus()) || "SURRENDERED".equals(policy.getStatus());
        policy.mature();
        policyRepository.save(policy);
        if (alreadyClosed) {
            return; // idempotent on repeat -- no second event
        }
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicyMatured", tenantId,
            Map.of("policyNumber", policyNumber,
                   "maturedAt", Instant.now().toString())));
    }

    @Override
    @Transactional
    public void terminateForSettledClaim(String policyNumber, UUID claimId, String terminatedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicyOrThrow(policyNumber, tenantId);
        // Either terminal status, not just SURRENDERED -- same M6 final-review C1 part 2 reasoning
        // as markMatured above (a policy already MATURED stays MATURED, so no PolicySurrendered).
        boolean alreadyClosed = "SURRENDERED".equals(policy.getStatus()) || "MATURED".equals(policy.getStatus());
        policy.terminateForSettledClaim();
        policyRepository.save(policy);
        if (alreadyClosed) {
            return; // idempotent on repeat -- no second event
        }
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PolicySurrendered", tenantId,
            Map.of("policyNumber", policyNumber,
                   "claimId", claimId,
                   "surrenderedAt", Instant.now().toString())));
    }

    private List<Beneficiary> validateAndBuildBeneficiaries(UUID tenantId, String policyNumber, List<BeneficiaryInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        BigDecimal totalShare = BigDecimal.ZERO;
        List<Beneficiary> built = new ArrayList<>();
        for (BeneficiaryInput input : inputs) {
            boolean hasParty = input.partyId() != null;
            boolean hasFreeform = input.freeformDesignee() != null && !input.freeformDesignee().isBlank();
            if (hasParty == hasFreeform) { // both true or both false -- neither is valid
                throw new BeneficiaryValidationException("Each beneficiary must have exactly one of partyId or freeformDesignee, not both or neither");
            }
            if (input.type() == BeneficiaryType.PARTY && !hasParty) {
                throw new BeneficiaryValidationException("Beneficiary type PARTY requires partyId");
            }
            if (input.type() == BeneficiaryType.FREEFORM && !hasFreeform) {
                throw new BeneficiaryValidationException("Beneficiary type FREEFORM requires freeformDesignee");
            }
            totalShare = totalShare.add(input.sharePercent());
            built.add(new Beneficiary(tenantId, policyNumber, input.type().name(), input.partyId(), input.freeformDesignee(),
                input.sharePercent(), input.revocable()));
        }
        if (totalShare.compareTo(new BigDecimal("100")) != 0) {
            throw new BeneficiaryValidationException("Beneficiary shares must sum to 100, got " + totalShare);
        }
        return built;
    }

    private Policy findPolicyOrThrow(String policyNumber, UUID tenantId) {
        return policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }

    private PolicyView toView(Policy policy) {
        PolicyAccount account = policyAccountRepository.findById(policy.getPolicyNumber()).orElse(null);
        List<BeneficiaryView> beneficiaryViews = beneficiaryRepository.findByPolicyNumberAndActiveTrue(policy.getPolicyNumber()).stream()
            .map(b -> new BeneficiaryView(b.getBeneficiaryId(), BeneficiaryType.valueOf(b.getBeneficiaryType()), b.getPartyId(),
                b.getFreeformDesignee(), b.getSharePercent(), b.isRevocable()))
            .toList();
        return new PolicyView(policy.getPolicyNumber(), policy.getUnderwritingCaseId(), policy.getPolicyholderPartyId(), policy.getProductId(), policy.getProductVersionId(),
            policy.getAgentOfRecordId(), PolicyStatus.valueOf(policy.getStatus()), policy.getIssueDate(),
            policy.getSumAssuredAmount(), policy.getSumAssuredCurrency(),
            account != null ? account.getCashValueAmount() : BigDecimal.ZERO,
            account != null ? account.getCashValueCurrency() : policy.getSumAssuredCurrency(),
            policy.getPremiumAmount(), policy.getPremiumCurrency(), policy.getPremiumFrequency(),
            beneficiaryViews);
    }
}
