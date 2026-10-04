package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import java.util.UUID;

/** No funeral application on this case -- also the answer for every non-funeral case. */
class FuneralApplicationNotFoundException extends RuntimeException {
    FuneralApplicationNotFoundException(UUID caseId) {
        super("Case " + caseId + " has no funeral application");
    }
}
