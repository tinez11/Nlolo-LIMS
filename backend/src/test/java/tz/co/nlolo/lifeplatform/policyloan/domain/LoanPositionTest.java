package tz.co.nlolo.lifeplatform.policyloan.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A loan's position, interest first (IFRS 17 I3b, guide E-03): what the ledger's 2126 and 2125 must agree with. */
class LoanPositionTest {

    private static LoanTransaction tx(String type, String amount) {
        return new LoanTransaction(UUID.randomUUID(), UUID.randomUUID(), type, new BigDecimal(amount), "TZS", null);
    }

    @Test
    void aRepaymentPaysTheInterestAccruedFirstThenPrincipal() {
        LoanPosition position = LoanPosition.of(new BigDecimal("1000000.00"), List.of(
            tx("INTEREST_ACCRUAL", "10000.00"), tx("REPAYMENT", "210000.00"), tx("INTEREST_ACCRUAL", "8000.00")));
        assertThat(position.principalOutstanding()).isEqualByComparingTo("800000.00");
        assertThat(position.interestOutstanding()).isEqualByComparingTo("8000.00");
        assertThat(position.interestPartOf(new BigDecimal("5000.00"))).isEqualByComparingTo("5000.00");
        assertThat(position.interestPartOf(new BigDecimal("50000.00"))).isEqualByComparingTo("8000.00");
    }

    @Test
    void aNewLoanOwesItsPrincipalAndNoInterest() {
        LoanPosition position = LoanPosition.of(new BigDecimal("500000.00"), List.of(tx("DISBURSEMENT", "500000.00")));
        assertThat(position.principalOutstanding()).isEqualByComparingTo("500000.00");
        assertThat(position.interestOutstanding()).isZero();
        assertThat(position.interestPartOf(new BigDecimal("1000.00"))).isZero();
    }
}
