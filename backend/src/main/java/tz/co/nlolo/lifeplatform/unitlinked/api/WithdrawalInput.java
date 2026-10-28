package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A partial withdrawal as staff record it (U2, spec §3): the GROSS amount to sell, optionally named funds with an amount
 * each (summing to the gross; none = pro rata by value at the latest prices), and where to pay it.
 */
public record WithdrawalInput(BigDecimal grossAmount, List<Named> funds, String payeeRef,
                              /* How it is paid (2026-10-28): blank or MOBILE_MONEY = payeeRef is the number; EFT = the
                                 four bank fields; CHEQUE = payeeRef is who it is made out to. */
                              String method, String payeeBank, String payeeBranch, String payeeAccountName,
                              String payeeAccountNumber) {

    /** Mobile money to {@code payeeRef}, as every withdrawal before 2026-10-28. */
    public WithdrawalInput(BigDecimal grossAmount, List<Named> funds, String payeeRef) {
        this(grossAmount, funds, payeeRef, null, null, null, null, null);
    }

    /** The route, or IllegalArgumentException naming what is missing. */
    public tz.co.nlolo.lifeplatform.PayoutRoute route() {
        return tz.co.nlolo.lifeplatform.PayoutRoute.of(method, payeeRef, payeeBank, payeeBranch, payeeAccountName, payeeAccountNumber);
    }

    public record Named(String fundCode, BigDecimal amount) {}
}
