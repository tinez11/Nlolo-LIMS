package tz.co.nlolo.lifeplatform.underwriting.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public interface UnderwritingApi {
    UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String openedBy);
    UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy);
    UnderwritingCaseView getCase(UUID caseId);
    /**
     * @param applicantPartyId a single applicant, for the client register's underwriting panel;
     *                         null means no filter on that dimension.
     * @param applicantPartyIds the agent scope -- NULL for staff (no scope), an EMPTY set for an
     *                          agent who has registered nobody (scope to nothing, zero rows). The
     *                          caller resolves it via {@code PartyApi.partyIdsRegisteredBy}; a case
     *                          carries no agent reference of its own.
     */
    Page<UnderwritingCaseView> listCases(UnderwritingCaseStatus status, UUID applicantPartyId,
                                          Set<UUID> applicantPartyIds, Pageable pageable);
    void referToSeniorUnderwriter(UUID caseId);
    boolean checkContestability(UUID caseId, LocalDate asOfDate);
}
