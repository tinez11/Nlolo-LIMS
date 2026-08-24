package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.InvalidRecoveryStateException;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaimRecoveryStateMachineTest {

    private static ClaimRecovery newRecovery() {
        return new ClaimRecovery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("5000.00"), "TZS", "system:test");
    }

    @Test
    void aFreshRecoveryIsUnconfirmed() {
        ClaimRecovery recovery = newRecovery();
        assertThat(recovery.isConfirmed()).isFalse();
        assertThat(recovery.getConfirmedAt()).isNull();
    }

    @Test
    void confirmingStampsTheTimestampAndActor() {
        ClaimRecovery recovery = newRecovery();
        Instant when = Instant.parse("2026-03-01T10:00:00Z");
        recovery.confirm(when, "finance-officer");
        assertThat(recovery.isConfirmed()).isTrue();
        assertThat(recovery.getConfirmedAt()).isEqualTo(when);
        assertThat(recovery.getUpdatedBy()).isEqualTo("finance-officer");
    }

    /** The caller (ReinsuranceApiImpl, Task 7) must be able to tell a real transition from a
     * repeat, because RecoveryConfirmed may be published only on the former -- M6's I1 finding.
     * Throwing rather than silently returning makes a double-confirm a 409, not a phantom success. */
    @Test
    void confirmingTwiceIsRejected() {
        ClaimRecovery recovery = newRecovery();
        recovery.confirm(Instant.now(), "finance-officer");
        assertThrows(InvalidRecoveryStateException.class,
            () -> recovery.confirm(Instant.now(), "finance-officer-2"));
    }
}
