package tz.co.nlolo.lifeplatform.payment;

import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review fix (C2): the state machine both ledgers now share, asserted directly rather than only
 * through an integration path. Plain unit tests -- no Spring, no container -- because the rules
 * being pinned here are pure invariants of the two aggregates, and an IN_DOUBT row that cannot be
 * resolved by a later callback is the specific regression this whole fix exists to prevent.
 *
 * <p>What each rule is protecting against, spelled out because the transitions look interchangeable
 * and are not:
 * <ul>
 *   <li><b>IN_DOUBT -> terminal must be allowed.</b> If it throws, a later genuine SUCCESS callback
 *       is swallowed by {@code MobileMoneyCallbackController}'s catch-all, the aggregator gets its
 *       200 and stops retrying, and a real payout is stranded permanently.</li>
 *   <li><b>terminal -> IN_DOUBT must throw.</b> Once the rail has told us definitively, a later
 *       ambiguity does not un-tell us; walking a row back out of a terminal state would make the
 *       ledger's own history unreliable.</li>
 *   <li><b>the terminal-conflict rejection must survive.</b> COMPLETED -> FAILED still throws; the
 *       widening admits IN_DOUBT as a source, it does not make the state machine permissive.</li>
 * </ul>
 */
class PaymentLedgerStateMachineTest {

    private static DisbursementInstruction disbursement() {
        return new DisbursementInstruction(UUID.randomUUID(), "key-" + UUID.randomUUID(), "MPESA-0700000000",
            new BigDecimal("1000.00"), "TZS", "LOAN_DISBURSEMENT", UUID.randomUUID().toString());
    }

    private static PaymentTransaction collection() {
        return new PaymentTransaction(UUID.randomUUID(), "key-" + UUID.randomUUID(), "MPESA-0700000000",
            new BigDecimal("1000.00"), "TZS", UUID.randomUUID().toString());
    }

    @Test
    void anInDoubtDisbursementCanStillBeCompletedByALaterGenuineSuccessCallback() {
        DisbursementInstruction instruction = disbursement();
        instruction.markInDoubt(null);
        assertThat(instruction.getStatus()).isEqualTo("IN_DOUBT");
        // gateway_reference stays null -- exactly the condition that makes C1's id-keyed resolver
        // the ONLY way this row can be found by the callback that resolves it.
        assertThat(instruction.getGatewayReference()).isNull();

        instruction.markCompleted("MM-LATE-SUCCESS");

        assertThat(instruction.getStatus()).isEqualTo("COMPLETED");
        // And the reference is repaired on the way through, so any redelivery resolves via the
        // primary gateway_reference route from now on.
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-LATE-SUCCESS");
    }

    @Test
    void anInDoubtDisbursementCanAlsoBeFailedByALaterGenuineDeclineCallback() {
        DisbursementInstruction instruction = disbursement();
        instruction.markInDoubt(null);

        instruction.markFailed("MM-LATE-DECLINE");

        assertThat(instruction.getStatus()).isEqualTo("FAILED");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-LATE-DECLINE");
    }

    @Test
    void anInDoubtCollectionCanStillBeConfirmedOrFailedByALaterCallback() {
        PaymentTransaction confirmed = collection();
        confirmed.markInDoubt(null);
        confirmed.markConfirmed("MM-LATE-CONFIRM");
        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getGatewayReference()).isEqualTo("MM-LATE-CONFIRM");

        PaymentTransaction failed = collection();
        failed.markInDoubt(null);
        failed.markFailed("MM-LATE-FAIL");
        assertThat(failed.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void markInDoubtIsIdempotentOnRepeatAndNeverDropsAnAlreadyRecordedReference() {
        DisbursementInstruction instruction = disbursement();
        instruction.markInDoubt("MM-PARTIAL-REF");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-PARTIAL-REF");

        // A second indeterminate observation, this time with nothing attached, must not erase the
        // one correlation handle the row already has -- that would recreate C1's unrecoverable row.
        instruction.markInDoubt(null);

        assertThat(instruction.getStatus()).isEqualTo("IN_DOUBT");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-PARTIAL-REF");
    }

    @Test
    void aTerminalRowCanNeverBeWalkedBackIntoInDoubt() {
        DisbursementInstruction completed = disbursement();
        completed.markCompleted("MM-DONE");
        assertThatThrownBy(() -> completed.markInDoubt(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMPLETED");

        DisbursementInstruction failed = disbursement();
        failed.markFailed("MM-NOPE");
        assertThatThrownBy(() -> failed.markInDoubt(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FAILED");

        PaymentTransaction confirmed = collection();
        confirmed.markConfirmed("MM-DONE");
        assertThatThrownBy(() -> confirmed.markInDoubt(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CONFIRMED");
    }

    @Test
    void wideningTheSourceStatesDidNotMakeConflictingTerminalTransitionsPermissive() {
        DisbursementInstruction completed = disbursement();
        completed.markCompleted("MM-DONE");
        assertThatThrownBy(() -> completed.markFailed("MM-CONTRADICTION"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMPLETED");

        DisbursementInstruction failed = disbursement();
        failed.markFailed("MM-NOPE");
        assertThatThrownBy(() -> failed.markCompleted("MM-CONTRADICTION"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FAILED");

        PaymentTransaction confirmed = collection();
        confirmed.markConfirmed("MM-DONE");
        assertThatThrownBy(() -> confirmed.markFailed("MM-CONTRADICTION"))
            .isInstanceOf(IllegalStateException.class);

        // ...and the same-outcome repeat is still a silent no-op, not an error.
        DisbursementInstruction repeat = disbursement();
        repeat.markCompleted("MM-DONE");
        repeat.markCompleted("MM-DONE");
        assertThat(repeat.getStatus()).isEqualTo("COMPLETED");
    }
}
