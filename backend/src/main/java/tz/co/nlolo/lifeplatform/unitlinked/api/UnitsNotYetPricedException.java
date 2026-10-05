package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 409 UNITS_NOT_YET_PRICED: a unit-linked death's value depends on units sold at the first price after the death
 * was registered, and that price is not approved yet. Approving before then would pay a figure nobody knows.
 */
public class UnitsNotYetPricedException extends RuntimeException {

    public UnitsNotYetPricedException(List<String> fundCodes, LocalDate boundDate) {
        super("The payable amount is waiting for the unit prices of " + boundDate + " for " + String.join(", ", fundCodes)
            + ": the units are sold at the first price after the death was registered");
    }
}
