package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface UnderwritingApi {
    UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String openedBy);
    UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy);
    UnderwritingCaseView getCase(UUID caseId);
    void referToSeniorUnderwriter(UUID caseId);
    boolean checkContestability(UUID caseId, LocalDate asOfDate);
}
