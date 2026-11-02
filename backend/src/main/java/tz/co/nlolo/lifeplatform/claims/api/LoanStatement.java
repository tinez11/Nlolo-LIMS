package tz.co.nlolo.lifeplatform.claims.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * What a credit-life borrower still owed the lender on the date of the event, from the lender's own statement
 * (2026-10-30). The claim pays this -- the principal, and the interest only where the lender's agreement covers
 * interest -- capped at the borrower's cover. Amounts are decimal strings on the wire, as all money here travels.
 *
 * <p>Stored inside the claim's JSONB details, so it needs no migration.
 *
 * @param principalOutstanding the loan principal still owed on the date of the event
 * @param interestOutstanding the interest owed on that date, or null where the statement shows none
 */
public record LoanStatement(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal principalOutstanding,
                            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal interestOutstanding) {

    /** What the lender lost: the principal, with the interest where the agreement covers it. */
    public BigDecimal owed(boolean coverIncludesInterest) {
        return coverIncludesInterest && interestOutstanding != null
            ? principalOutstanding.add(interestOutstanding) : principalOutstanding;
    }
}
