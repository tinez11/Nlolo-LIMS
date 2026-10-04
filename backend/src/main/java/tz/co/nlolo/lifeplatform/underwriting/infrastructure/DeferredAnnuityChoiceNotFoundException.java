package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import java.util.UUID;

/** No retirement age on this case -- also the answer for a case that is not a deferred annuity's. A 404. */
class DeferredAnnuityChoiceNotFoundException extends RuntimeException {
    DeferredAnnuityChoiceNotFoundException(UUID caseId) {
        super("Case " + caseId + " records no retirement age");
    }
}
