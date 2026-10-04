package tz.co.nlolo.lifeplatform.underwriting.api;

import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.time.LocalDate;
import java.util.List;

/**
 * A funeral applicant's plan and the dependants to cover. The main member is not among {@code dependants}:
 * they are the case's life assured, a registered party.
 *
 * @param quote the whole family priced on the plan, re-derived on every read (never stored) -- the main
 *              member first, then the dependants in the order they were listed
 */
public record FuneralApplication(String planCode, List<Life> dependants, FuneralQuote quote) {

    public FuneralApplication {
        dependants = dependants != null ? List.copyOf(dependants) : List.of();
    }

    /** A dependant: a name on the policy, not a registered party (credit life's FREEFORM reason). */
    public record Life(FuneralRole role, String fullName, LocalDate dateOfBirth, String sex, String idNumber,
                       boolean student) {}
}
