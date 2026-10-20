package tz.co.nlolo.lifeplatform.underwriting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationApi;
import tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationView;
import tz.co.nlolo.lifeplatform.underwriting.api.QuotationNotFoundException;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;
import tz.co.nlolo.lifeplatform.underwriting.domain.AcceptedQuotation;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.AcceptedQuotationRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** {@link AcceptedQuotationApi}: one agent's agreed quotations, kept only once a proposal is opened from them. */
@Service
public class AcceptedQuotations implements AcceptedQuotationApi {

    private static final Logger log = LoggerFactory.getLogger(AcceptedQuotations.class);

    private final AcceptedQuotationRepository quotations;
    private final TransactionTemplate tx;

    private final ProposalArrivals arrivals;
    private final tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository cases;

    public AcceptedQuotations(AcceptedQuotationRepository quotations, PlatformTransactionManager transactionManager,
                              ProposalArrivals arrivals,
                              tz.co.nlolo.lifeplatform.underwriting.infrastructure.UnderwritingCaseRepository cases) {
        this.quotations = quotations;
        this.tx = new TransactionTemplate(transactionManager);
        this.arrivals = arrivals;
        this.cases = cases;
    }

    @Override
    @Transactional
    public AcceptedQuotationView accept(String ownerSubject, UUID applicantPartyId, UUID productId,
                                        UUID productVersionId, String inputs, String quoted, LocalDate quotedOn,
                                        LocalDate validUntil) {
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new UnderwritingValidationException("A quotation is accepted by a named agent");
        }
        return view(quotations.save(new AcceptedQuotation(TenantContext.get(), ownerSubject, applicantPartyId,
            productId, productVersionId, inputs, quoted, quotedOn, validUntil)));
    }

    @Override
    @Transactional(readOnly = true)
    public AcceptedQuotationView get(UUID quotationId, String ownerSubject) {
        return view(load(quotationId, ownerSubject));
    }

    @Override
    @Transactional
    public void discard(UUID quotationId, String ownerSubject) {
        AcceptedQuotation q = load(quotationId, ownerSubject);
        if (q.getCaseId() != null) {
            throw new UnderwritingValidationException("This quotation was submitted as a proposal and is kept with it");
        }
        quotations.delete(q);
    }

    @Override
    @Transactional
    public AcceptedQuotationView markSubmitted(UUID quotationId, String ownerSubject, UUID caseId,
                                               UUID applicantPartyId) {
        AcceptedQuotation q = load(quotationId, ownerSubject);
        // Conditional on the row, not on the read above: a second submission racing this one finds nothing to update
        // and is refused, which rolls back the case it opened instead of leaving it orphaned.
        if (q.getCaseId() != null || quotations.markSubmittedIfNot(quotationId, q.getTenantId(), caseId,
                applicantPartyId, Instant.now()) == 0) {
            throw new UnderwritingValidationException("This quotation was already submitted as a proposal");
        }
        // A new proposal from an agent: the underwriting team is told, after the commit (the alert email).
        cases.findByCaseIdAndTenantId(caseId, q.getTenantId()).ifPresent(c -> arrivals.announce(c, 1));
        return view(load(quotationId, ownerSubject));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AcceptedQuotationView> forCase(UUID caseId) {
        return quotations.findByCaseIdAndTenantId(caseId, TenantContext.get()).map(AcceptedQuotations::view);
    }

    /** Selection across tenants, ids only, each deleted under its own tenant in its own transaction. */
    @Override
    public int discardUnsubmittedBefore(Instant before) {
        int discarded = 0;
        for (Object[] row : quotations.unsubmittedBefore(before)) {
            UUID quotationId = (UUID) row[0];
            UUID previous = TenantContext.getOrNull();
            TenantContext.set((UUID) row[1]);
            try {
                Boolean gone = tx.execute(status -> quotations.findByQuotationIdAndTenantId(quotationId, TenantContext.get())
                    .filter(q -> q.getCaseId() == null)
                    .map(q -> { quotations.delete(q); return true; })
                    .orElse(false));
                if (Boolean.TRUE.equals(gone)) discarded++;
            } catch (RuntimeException e) {
                log.error("Failed to discard unsubmitted quotation {}", quotationId, e);
            } finally {
                if (previous != null) TenantContext.set(previous); else TenantContext.clear();
            }
        }
        return discarded;
    }

    private AcceptedQuotation load(UUID quotationId, String ownerSubject) {
        return quotations.findByQuotationIdAndTenantId(quotationId, TenantContext.get())
            .filter(q -> ownerSubject == null || ownerSubject.equals(q.getOwnerSubject()))
            .orElseThrow(() -> new QuotationNotFoundException(quotationId));
    }

    private static AcceptedQuotationView view(AcceptedQuotation q) {
        return new AcceptedQuotationView(q.getQuotationId(), q.getOwnerSubject(), q.getApplicantPartyId(),
            q.getProductId(), q.getProductVersionId(), q.getInputs(), q.getQuoted(), q.getQuotedOn(),
            q.getValidUntil(), q.getCaseId(), q.getSubmittedAt());
    }
}
