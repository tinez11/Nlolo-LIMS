package tz.co.nlolo.lifeplatform.policyloan.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person is told when a loan repayment cannot be asked for (2026-11-14, the user: "users are going to use [this],
 * not software engineers"): no loan id, no status code, the amount as money.
 */
class LoanRefusalWordsTest {

    @Test
    void theAmountOwedReadsAsMoney() {
        assertThat(PolicyLoanApiImpl.money(new BigDecimal("20000"), "TZS")).isEqualTo("TZS 20,000.00");
        assertThat(PolicyLoanApiImpl.money(new BigDecimal("1234567.891"), "TZS")).isEqualTo("TZS 1,234,567.89");
    }

    @Test
    void aLoanThatTakesNoRepaymentSaysWhyInWords() {
        assertThat(PolicyLoanApiImpl.nothingToRepay("SETTLED")).isEqualTo("This loan is fully repaid. There is nothing to ask for.");
        assertThat(PolicyLoanApiImpl.nothingToRepay("AWAITING_PAYOUT_APPROVAL"))
            .isEqualTo("This loan has not been paid out yet, so nothing is owed on it.");
        for (String status : new String[] {"RESERVED_PENDING_ORIGINATION", "ORIGINATED", "AWAITING_PAYOUT_APPROVAL",
                "PAYOUT_REFUSED", "DISBURSEMENT_REQUESTED", "SETTLED", "FORCED_LAPSE_TRIGGERED", "DISBURSEMENT_FAILED"}) {
            assertThat(PolicyLoanApiImpl.nothingToRepay(status)).doesNotContain("_").doesNotContain(status);
        }
    }
}
