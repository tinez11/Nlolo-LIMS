package tz.co.nlolo.lifeplatform.underwriting.api;

import java.util.UUID;

public class UnderwritingCaseNotFoundException extends RuntimeException {
    public UnderwritingCaseNotFoundException(UUID caseId) {
        super("No underwriting case found for id " + caseId);
    }
}
