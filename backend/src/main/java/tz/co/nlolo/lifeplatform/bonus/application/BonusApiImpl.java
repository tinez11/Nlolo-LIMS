package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.api.*;
import tz.co.nlolo.lifeplatform.bonus.domain.Declaration;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.DeclarationRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.ParticipantRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class BonusApiImpl implements BonusApi {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private final DeclarationRepository declarations;
    private final ParticipantRepository participants;
    private final BonusIdempotentRequests keyed;

    public BonusApiImpl(DeclarationRepository declarations, ParticipantRepository participants, BonusIdempotentRequests keyed) {
        this.declarations = declarations;
        this.participants = participants;
        this.keyed = keyed;
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
