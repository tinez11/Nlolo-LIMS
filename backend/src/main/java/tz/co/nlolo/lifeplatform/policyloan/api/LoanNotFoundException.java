package tz.co.nlolo.lifeplatform.policyloan.api;

import java.util.UUID;

public class LoanNotFoundException extends RuntimeException {
    public LoanNotFoundException(UUID loanId) {
        super("No loan found for id " + loanId);
    }
}
