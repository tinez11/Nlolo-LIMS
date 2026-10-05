package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A partial withdrawal as staff record it (U2, spec §3): the GROSS amount to sell, optionally named funds with an amount
 * each (summing to the gross; none = pro rata by value at the latest prices), and where to pay it.
 */
public record WithdrawalInput(BigDecimal grossAmount, List<Named> funds, String payeeRef) {

    public record Named(String fundCode, BigDecimal amount) {}
}
