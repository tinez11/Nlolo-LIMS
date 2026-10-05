package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import java.util.UUID;

/** No unit-linked choice on this case -- also the answer for every non-unit-linked case. */
class UnitLinkedChoiceNotFoundException extends RuntimeException {
    UnitLinkedChoiceNotFoundException(UUID caseId) {
        super("Case " + caseId + " has no unit-linked fund choice");
    }
}
