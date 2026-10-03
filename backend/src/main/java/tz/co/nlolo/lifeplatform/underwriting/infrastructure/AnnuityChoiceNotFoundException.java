package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import java.util.UUID;

/** No annuity choice on this case -- also the answer for a case that is not an annuity's. A 404. */
class AnnuityChoiceNotFoundException extends RuntimeException {
    AnnuityChoiceNotFoundException(UUID caseId) {
        super("Case " + caseId + " records no annuity choice");
    }
}
