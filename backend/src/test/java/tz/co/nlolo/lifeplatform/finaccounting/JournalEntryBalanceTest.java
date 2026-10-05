package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JournalEntryBalanceTest {

    private static JournalEntry newEntry() {
        return new JournalEntry(UUID.randomUUID(), "billing.PremiumCollected", "inv-1",
            "2026-08", "POL-0001", "system:test");
    }

    @Test
    void aFreshEntryHasNoLegsAndIsNotBalanced() {
        JournalEntry entry = newEntry();
        assertThat(entry.getLegs()).isEmpty();
        // An empty entry is trivially equal on both sides, but it is not a valid entry --
        // isBalanced must require at least one leg on each side, not merely 0 == 0.
        assertThat(entry.isBalanced()).isFalse();
    }

    @Test
    void aMatchedDebitAndCreditBalances() {
        JournalEntry entry = newEntry();
        entry.addLeg("1140", PostingDirection.DR, new BigDecimal("15000.00"), "TZS");
        entry.addLeg("2122", PostingDirection.CR, new BigDecimal("15000.00"), "TZS");
        assertThat(entry.isBalanced()).isTrue();
        assertThat(entry.getLegs()).hasSize(2);
    }

    @Test
    void mismatchedAmountsDoNotBalance() {
        JournalEntry entry = newEntry();
        entry.addLeg("1140", PostingDirection.DR, new BigDecimal("15000.00"), "TZS");
        entry.addLeg("2122", PostingDirection.CR, new BigDecimal("14999.99"), "TZS");
        assertThat(entry.isBalanced()).isFalse();
    }

    @Test
    void twoLegsOnTheSameSideDoNotBalance() {
        JournalEntry entry = newEntry();
        entry.addLeg("1140", PostingDirection.DR, new BigDecimal("100.00"), "TZS");
        entry.addLeg("2122", PostingDirection.DR, new BigDecimal("100.00"), "TZS");
        assertThat(entry.isBalanced()).isFalse();
    }

    /** Both legs of one entry must share a currency. No FX table exists anywhere on this
     * platform, so a mixed-currency entry could never be meaningfully balanced. */
    @Test
    void aMixedCurrencyEntryIsRejected() {
        JournalEntry entry = newEntry();
        entry.addLeg("1140", PostingDirection.DR, new BigDecimal("100.00"), "TZS");
        assertThrows(IllegalArgumentException.class,
            () -> entry.addLeg("2122", PostingDirection.CR, new BigDecimal("100.00"), "USD"));
    }

    /** amount is a positive magnitude; direction carries the sign (V2's
     * gl_posting_amount_positive CHECK). A negative leg must never be constructible. */
    @Test
    void aNonPositiveLegIsRejected() {
        JournalEntry entry = newEntry();
        assertThrows(IllegalArgumentException.class,
            () -> entry.addLeg("1140", PostingDirection.DR, new BigDecimal("-1.00"), "TZS"));
        assertThrows(IllegalArgumentException.class,
            () -> entry.addLeg("1140", PostingDirection.DR, BigDecimal.ZERO, "TZS"));
    }
}
