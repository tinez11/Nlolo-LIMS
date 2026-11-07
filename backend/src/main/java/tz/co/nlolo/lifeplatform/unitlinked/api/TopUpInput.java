package tz.co.nlolo.lifeplatform.unitlinked.api;

import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A top-up as staff record it (U2): the amount, who pays it, and optionally its own split (else the one in force).
 *
 * <p>{@code method}, {@code reference} and {@code receivedOn} (2026-11-07, the user: every method on the one Deposit
 * page): null or MOBILE_MONEY asks the payer's phone; cash, a bank deposit, a cheque, a Lipa number or TANQR is money
 * already received, recorded with the method's own reference and day, and confirmed by a second finance person.
 */
public record TopUpInput(BigDecimal amount, String payerRef, List<UnitLinkedChoice.Split> split, String method,
                         String reference, LocalDate receivedOn) {

    public TopUpInput(BigDecimal amount, String payerRef, List<UnitLinkedChoice.Split> split) {
        this(amount, payerRef, split, null, null, null);
    }

    /** Money already received by hand, rather than asked of a phone. */
    public boolean received() {
        return method != null && !method.isBlank() && !"MOBILE_MONEY".equals(method);
    }
}
