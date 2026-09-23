package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure arithmetic, no container. The whole point of Plan 5 lives in these assertions: member
 * cover moves the STOCK metric and leaves the FLOW metric alone.
 *
 * <p>A group scheme's sum assured is the total of its member schedule, and until now nothing
 * updated it after activation — {@code policy.GroupMemberAdded} and {@code GroupMemberExited}
 * had no consumer at all. On credit life members arrive monthly in files of several hundred and
 * leave on every settled claim, so {@code SUM_ASSURED_IN_FORCE} drifted continuously and in both
 * directions (design spec §2.14).
 */
class PolicyMovementArithmeticTest {

    private static final String PERIOD = "2026-Q3";

    private PolicyMovement movement() {
        return new PolicyMovement(UUID.randomUUID(), PERIOD, UUID.randomUUID(), "TZS");
    }

    @Test
    void memberCoverAddedRaisesInForceSumAssuredWithoutTouchingNewBusiness() {
        // THE POINT OF THIS TASK. A borrower enrolled onto an existing scheme is more cover in
        // force. Whether they are also "new business written" is an actuarial question nobody has
        // answered, so this must not move that number by accident -- see the note on
        // MetricReaderRegistry.sumSumAssuredIssued for why the default is to leave it alone
        // rather than a claim that leaving it alone is right.
        PolicyMovement m = movement();
        m.applyIssued(new BigDecimal("1000000.00"));
        m.applyMemberCoverAdded(new BigDecimal("250000.00"));

        assertThat(MetricReaderRegistry.cumulativeSumAssured(List.of(m), PERIOD))
            .isEqualByComparingTo("1250000.00");
        assertThat(MetricReaderRegistry.sumSumAssuredIssued(List.of(m)))
            .isEqualByComparingTo("1000000.00");
    }

    @Test
    void memberCoverExitedLowersInForceSumAssuredWithoutTouchingNewBusiness() {
        PolicyMovement m = movement();
        m.applyIssued(new BigDecimal("1000000.00"));
        m.applyMemberCoverExited(new BigDecimal("400000.00"));

        assertThat(MetricReaderRegistry.cumulativeSumAssured(List.of(m), PERIOD))
            .isEqualByComparingTo("600000.00");
        assertThat(MetricReaderRegistry.sumSumAssuredIssued(List.of(m)))
            .isEqualByComparingTo("1000000.00");
    }

    @Test
    void memberCoverDoesNotMoveAnyPolicyCount() {
        // A scheme is ONE policy however many borrowers sit on it. Counting a joiner as a policy
        // would report one lender's monthly file as several hundred new contracts.
        PolicyMovement m = movement();
        m.applyIssued(new BigDecimal("1000000.00"));
        m.applyMemberCoverAdded(new BigDecimal("250000.00"));
        m.applyMemberCoverExited(new BigDecimal("100000.00"));

        assertThat(MetricReaderRegistry.cumulativePolicyCount(List.of(m), PERIOD)).isEqualTo(1);
    }

    @Test
    void bothMeasuresAreGrossAndRefuseANegativeAmount() {
        // The columns carry CHECK (>= 0). A caller passing a signed delta straight through would
        // fail at the database with a constraint name and no explanation; fail here instead,
        // where the message can name the call that was wrong.
        PolicyMovement m = movement();
        assertThatThrownBy(() -> m.applyMemberCoverAdded(new BigDecimal("-1.00")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> m.applyMemberCoverExited(new BigDecimal("-1.00")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZeroDeltaIsAcceptedAndChangesNothing() {
        // The listener never calls these with zero -- it returns early on a zero delta -- but the
        // CHECK is >= 0 rather than > 0 for a reason V2 already documents: an upsert creates the
        // row with zeros and increments exactly one column. Refusing zero here would contradict
        // the column and make the guard say something the database does not.
        PolicyMovement m = movement();
        m.applyIssued(new BigDecimal("1000000.00"));
        m.applyMemberCoverAdded(BigDecimal.ZERO);

        assertThat(MetricReaderRegistry.cumulativeSumAssured(List.of(m), PERIOD))
            .isEqualByComparingTo("1000000.00");
    }
}
