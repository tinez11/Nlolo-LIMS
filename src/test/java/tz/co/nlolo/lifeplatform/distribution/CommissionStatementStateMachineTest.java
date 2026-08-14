package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.distribution.api.DistributionValidationException;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 3: a plain unit test over {@link CommissionStatement} -- no Spring, no container, no
 * database. Covers every legal transition and a representative sample of illegal ones, per the
 * task brief's Step 6: the full happy path OPEN -> CLOSED -> PAYOUT_REQUESTED -> PAID; the retry
 * path PAYOUT_REQUESTED -> PAYOUT_FAILED -> PAYOUT_REQUESTED (new key) -> PAID; idempotency on
 * each terminal transition; blank key/payeeRef rejection; zero/negative total rejection at
 * markPayoutRequested; markPayoutFailed as a no-op outside PAYOUT_REQUESTED; and recomputeTotal
 * producing a negative total from a clawback-heavy accrual list without throwing.
 */
class CommissionStatementStateMachineTest {

    private static final String CURRENCY = "TZS";

    private CommissionStatement newStatement() {
        return new CommissionStatement(UUID.randomUUID(), UUID.randomUUID(), "2026-08", CURRENCY);
    }

    /** Gives the statement a positive total so markPayoutRequested's "nothing to pay out" guard
     * does not block the transitions under test here. */
    private void givePositiveTotal(CommissionStatement statement) {
        CommissionAccrual accrual = new CommissionAccrual(statement.getTenantId(), statement.getAgentId(),
            "POL-0001", TierType.FIRST_YEAR, new BigDecimal("50000.00"), CURRENCY, statement.getPeriod(),
            "POL-0001", null, "test-staff");
        statement.recomputeTotal(List.of(accrual));
    }

    // ---- Happy path -------------------------------------------------------------------------

    @Test
    void happyPathRunsOpenThroughClosedPayoutRequestedToPaid() {
        CommissionStatement statement = newStatement();
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.OPEN);
        givePositiveTotal(statement);

        Instant closedAt = Instant.now();
        statement.close(closedAt);
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.CLOSED);
        assertThat(statement.getClosedAt()).isEqualTo(closedAt);

        statement.markPayoutRequested("idem-key-1", "payee-1");
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.PAYOUT_REQUESTED);
        assertThat(statement.getPayoutIdempotencyKey()).isEqualTo("idem-key-1");
        assertThat(statement.getPayeeRef()).isEqualTo("payee-1");

        Instant paidAt = Instant.now();
        statement.markPaid(paidAt);
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.PAID);
        assertThat(statement.getPaidAt()).isEqualTo(paidAt);
    }

    // ---- Retry path: PAYOUT_REQUESTED -> PAYOUT_FAILED -> PAYOUT_REQUESTED (new key) -> PAID --

    @Test
    void retryPathAfterPayoutFailureUsesANewIdempotencyKeyAndReachesPaid() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());
        statement.markPayoutRequested("idem-key-original", "payee-1");

        statement.markPayoutFailed("gateway timeout");
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.PAYOUT_FAILED);
        assertThat(statement.getPayoutFailureReason()).isEqualTo("gateway timeout");

        // Retry MUST use a new idempotency key -- payment dedupes on the old one.
        statement.markPayoutRequested("idem-key-retry", "payee-1");
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.PAYOUT_REQUESTED);
        assertThat(statement.getPayoutIdempotencyKey()).isEqualTo("idem-key-retry");
        assertThat(statement.getPayoutFailureReason()).isNull(); // fresh attempt clears the failure

        statement.markPaid(Instant.now());
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.PAID);
    }

    // ---- Illegal transitions ------------------------------------------------------------------

    @Test
    void closeRequiresOpen() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());
        statement.markPayoutRequested("idem-key", "payee-1");
        assertThrows(InvalidAgentStateException.class, () -> statement.close(Instant.now()));
    }

    @Test
    void markPayoutRequestedRequiresClosedOrPayoutFailed() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        assertThrows(InvalidAgentStateException.class,
            () -> statement.markPayoutRequested("idem-key", "payee-1"));
    }

    @Test
    void markPaidRequiresPayoutRequested() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());
        assertThrows(InvalidAgentStateException.class, () -> statement.markPaid(Instant.now()));
    }

    // ---- Idempotency on each terminal transition ----------------------------------------------

    @Test
    void closeIsIdempotent() {
        CommissionStatement statement = newStatement();
        Instant closedAt = Instant.now();
        statement.close(closedAt);
        statement.close(Instant.now().plusSeconds(60)); // second call is a silent no-op
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.CLOSED);
        assertThat(statement.getClosedAt()).isEqualTo(closedAt);
    }

    @Test
    void markPayoutRequestedIsIdempotent() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());
        statement.markPayoutRequested("idem-key-a", "payee-a");
        // second call, even with different arguments, is a silent no-op
        statement.markPayoutRequested("idem-key-b", "payee-b");
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.PAYOUT_REQUESTED);
        assertThat(statement.getPayoutIdempotencyKey()).isEqualTo("idem-key-a");
        assertThat(statement.getPayeeRef()).isEqualTo("payee-a");
    }

    @Test
    void markPaidIsIdempotent() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());
        statement.markPayoutRequested("idem-key", "payee-1");
        Instant paidAt = Instant.now();
        statement.markPaid(paidAt);
        statement.markPaid(Instant.now().plusSeconds(60));
        assertThat(statement.getStatus()).isEqualTo(StatementStatus.PAID);
        assertThat(statement.getPaidAt()).isEqualTo(paidAt);
    }

    // ---- markPayoutRequested validation --------------------------------------------------------

    @Test
    void markPayoutRequestedRejectsBlankIdempotencyKey() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());
        assertThrows(DistributionValidationException.class,
            () -> statement.markPayoutRequested("   ", "payee-1"));
        assertThrows(DistributionValidationException.class,
            () -> statement.markPayoutRequested(null, "payee-1"));
    }

    @Test
    void markPayoutRequestedRejectsBlankPayeeRef() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());
        assertThrows(DistributionValidationException.class,
            () -> statement.markPayoutRequested("idem-key", "   "));
        assertThrows(DistributionValidationException.class,
            () -> statement.markPayoutRequested("idem-key", null));
    }

    @Test
    void markPayoutRequestedRejectsZeroTotal() {
        CommissionStatement statement = newStatement();
        // totalAmount defaults to BigDecimal.ZERO -- never gave it a positive total
        statement.close(Instant.now());
        assertThrows(InvalidAgentStateException.class,
            () -> statement.markPayoutRequested("idem-key", "payee-1"));
    }

    @Test
    void markPayoutRequestedRejectsNegativeTotal() {
        CommissionStatement statement = newStatement();
        CommissionAccrual clawback = new CommissionAccrual(statement.getTenantId(), statement.getAgentId(),
            "POL-0001", TierType.FIRST_YEAR, new BigDecimal("-50000.00"), CURRENCY, statement.getPeriod(),
            "reversal-of-accrual", null, "test-staff");
        statement.recomputeTotal(List.of(clawback));
        assertThat(statement.getTotalAmount()).isEqualByComparingTo("-50000.00");
        statement.close(Instant.now());
        assertThrows(InvalidAgentStateException.class,
            () -> statement.markPayoutRequested("idem-key", "payee-1"));
    }

    // ---- markPayoutFailed ------------------------------------------------------------------------

    @Test
    void markPayoutFailedIsANoOpOutsidePayoutRequested() {
        CommissionStatement statement = newStatement();
        givePositiveTotal(statement);
        statement.close(Instant.now());

        statement.markPayoutFailed("should be ignored");

        assertThat(statement.getStatus()).isEqualTo(StatementStatus.CLOSED);
        assertThat(statement.getPayoutFailureReason()).isNull();
    }

    // ---- recomputeTotal ----------------------------------------------------------------------

    @Test
    void recomputeTotalProducesNegativeTotalFromClawbackHeavyAccrualsWithoutThrowing() {
        CommissionStatement statement = newStatement();
        CommissionAccrual issuance = new CommissionAccrual(statement.getTenantId(), statement.getAgentId(),
            "POL-0001", TierType.FIRST_YEAR, new BigDecimal("30000.00"), CURRENCY, statement.getPeriod(),
            "POL-0001", null, "test-staff");
        CommissionAccrual clawback1 = new CommissionAccrual(statement.getTenantId(), statement.getAgentId(),
            "POL-0002", TierType.FIRST_YEAR, new BigDecimal("-20000.00"), CURRENCY, statement.getPeriod(),
            "reversal-1", UUID.randomUUID(), "test-staff");
        CommissionAccrual clawback2 = new CommissionAccrual(statement.getTenantId(), statement.getAgentId(),
            "POL-0003", TierType.FIRST_YEAR, new BigDecimal("-25000.00"), CURRENCY, statement.getPeriod(),
            "reversal-2", UUID.randomUUID(), "test-staff");

        statement.recomputeTotal(List.of(issuance, clawback1, clawback2));

        assertThat(statement.getTotalAmount()).isEqualByComparingTo("-15000.00");
    }
}
