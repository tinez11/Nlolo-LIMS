package tz.co.nlolo.lifeplatform.bonus.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.api.*;
import tz.co.nlolo.lifeplatform.bonus.domain.*;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.*;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BonusApiImpl implements BonusApi {

    private static final Logger log = LoggerFactory.getLogger(BonusApiImpl.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final DeclarationRepository declarations;
    private final ParticipantRepository participants;
    private final BonusIdempotentRequests keyed;
    private final StatusEventRepository statusEvents;
    private final AttachmentEntryRepository entries;
    private final DeclarationOutcomeRepository outcomes;
    private final AttachmentLedger ledger;
    private final ProductApi productApi;
    private final BonusApiImpl self;

    /** {@code self} is this bean's proxy: the drain must reach attachOne through it, or REQUIRES_NEW is ignored. */
    public BonusApiImpl(DeclarationRepository declarations, ParticipantRepository participants, BonusIdempotentRequests keyed,
                        StatusEventRepository statusEvents, AttachmentEntryRepository entries,
                        DeclarationOutcomeRepository outcomes, AttachmentLedger ledger, ProductApi productApi,
                        @Lazy BonusApiImpl self) {
        this.declarations = declarations;
        this.participants = participants;
        this.keyed = keyed;
        this.statusEvents = statusEvents;
        this.entries = entries;
        this.outcomes = outcomes;
        this.ledger = ledger;
        this.productApi = productApi;
        this.self = self;
    }

    @Override
    @Transactional
    public BonusDeclarationView proposeDeclaration(UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                                                   BigDecimal terminalRatePercent, String proposedBy) {
        if (reversionaryRatePercent.signum() < 0 || reversionaryRatePercent.compareTo(HUNDRED) > 0) {
            throw new BonusStateException("A reversionary bonus rate must be between 0% and 100%");
        }
        if (terminalRatePercent.signum() < 0 || terminalRatePercent.compareTo(THOUSAND) > 0) {
            throw new BonusStateException("A terminal bonus rate must be between 0% and 1000% of attached bonuses");
        }
        // A declaration on a product that has never sold a with-profits policy attaches to nobody,
        // and is almost certainly a mistake.
        if (!participants.existsByProductId(productId)) {
            throw new BonusStateException("This product has no with-profits policies to declare a bonus on");
        }
        return toView(declarations.save(new Declaration(TenantContext.get(), productId, valuationDate,
            reversionaryRatePercent, terminalRatePercent, proposedBy)));
    }

    /** Not @Transactional: the guard opens the one transaction the create and its key share. */
    @Override
    public BonusDeclarationView proposeDeclaration(UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                                                   BigDecimal terminalRatePercent, String proposedBy, String idempotencyKey) {
        return keyed.once(idempotencyKey, "BONUS_DECLARATION", productId.toString(), proposedBy,
            () -> proposeDeclaration(productId, valuationDate, reversionaryRatePercent, terminalRatePercent, proposedBy),
            BonusDeclarationView::declarationId, id -> toView(load(id)));
    }

    @Override
    @Transactional
    public BonusDeclarationView approveDeclaration(UUID declarationId, String approvedBy) {
        Declaration d = load(declarationId);
        d.approve(approvedBy);
        try {
            // saveAndFlush so ux_declaration_valuation fires inside the catch, not at commit as a 500.
            return toView(declarations.saveAndFlush(d));
        } catch (DataIntegrityViolationException e) {
            throw new BonusStateException("A bonus is already declared for this product as at " + d.getValuationDate()
                + ". An approved declaration cannot be withdrawn.");
        }
    }

    @Override
    @Transactional
    public BonusDeclarationView withdrawDeclaration(UUID declarationId, String withdrawnBy) {
        Declaration d = load(declarationId);
        d.withdraw();
        return toView(declarations.save(d));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BonusDeclarationView> listDeclarations(UUID productId) {
        return declarations.findByProductIdOrderByValuationDateDescProposedAtDesc(productId).stream().map(this::toView).toList();
    }

    /**
     * One declaration, one policy: an outcome always, an entry only when a bonus attaches. Its own
     * transaction (REQUIRES_NEW through the proxy), so one policy's failure costs only that policy.
     * ux_declaration_outcome makes a re-run after a crash harmless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attachOne(UUID declarationId, String policyNumber) {
        UUID tenantId = TenantContext.get();
        Declaration d = load(declarationId);
        Participant p = participants.findById(policyNumber).orElseThrow();
        BonusPlan plan = productApi.resolveBonusPlan(p.getProductVersionId());
        List<Eligibility.StatusRow> rows = statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(policyNumber).stream()
            .map(StatusEvent::toRow).toList();
        Optional<String> refusal = Eligibility.refusal(rows, d.getValuationDate(), plan.paidUpParticipates());
        if (refusal.isPresent()) {
            outcomes.save(new DeclarationOutcome(tenantId, declarationId, policyNumber, OutcomeKind.NOT_ELIGIBLE, refusal.get()));
            return;
        }
        BigDecimal sumAssured = Eligibility.sumAssuredOn(rows, d.getValuationDate());
        BigDecimal attachedBefore = entries.sumBefore(policyNumber, d.getValuationDate());
        BigDecimal amount = BonusArithmetic.reversionary(plan.method(), sumAssured, attachedBefore, d.getReversionaryRatePercent());
        if (amount.signum() == 0) {
            outcomes.save(new DeclarationOutcome(tenantId, declarationId, policyNumber, OutcomeKind.NOTHING_DUE, null));
            return;
        }
        outcomes.saveAndFlush(new DeclarationOutcome(tenantId, declarationId, policyNumber, OutcomeKind.ATTACHED, null));
        ledger.attach(policyNumber, d, BonusArithmetic.base(plan.method(), sumAssured, attachedBefore), amount,
            "system:bonus-declaration");
    }

    /**
     * One batch of one declaration. Returns how many policies it DECIDED -- not how many it tried: a
     * policy that fails gets no outcome, so counting attempts would let one broken policy keep the
     * drain's loop spinning on it forever. Zero with an empty batch means complete.
     */
    public int drainDeclaration(UUID declarationId) {
        Declaration d = load(declarationId);
        List<String> batch = participants.awaitingOutcome(d.getProductId(), declarationId);
        if (batch.isEmpty()) {
            self.complete(declarationId);
            return 0;
        }
        int decided = 0;
        for (String policyNumber : batch) {
            try {
                self.attachOne(declarationId, policyNumber);
                decided++;
            } catch (Exception e) {
                log.error("Bonus declaration {} failed for policy {}", declarationId, policyNumber, e);
            }
        }
        return decided;
    }

    @Transactional
    public void complete(UUID declarationId) {
        Declaration d = load(declarationId);
        if (d.getCompletedAt() == null) {
            d.complete();
            declarations.save(d);
        }
    }

    Declaration load(UUID declarationId) {
        return declarations.findById(declarationId)
            .orElseThrow(() -> new BonusStateException("No bonus declaration " + declarationId));
    }

    BonusDeclarationView toView(Declaration d) {
        return new BonusDeclarationView(d.getDeclarationId(), d.getProductId(), d.getValuationDate(),
            d.getReversionaryRatePercent(), d.getTerminalRatePercent(), d.status(), d.getProposedBy(), d.getProposedAt(),
            d.getApprovedBy(), d.getApprovedAt(), d.getCompletedAt());
    }
}
