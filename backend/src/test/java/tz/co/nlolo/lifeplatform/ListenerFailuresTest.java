package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A refusal is done with; anything else is thrown on so the delivery stays open and is delivered again. */
class ListenerFailuresTest {

    static class PolicyNotFoundException extends RuntimeException {}

    @Test
    void aRefusalCountsTheDeliveryAsDone() {
        assertThatCode(() -> ListenerFailures.retryUnlessRefused(new IllegalStateException("already active")))
            .doesNotThrowAnyException();
        assertThatCode(() -> ListenerFailures.retryUnlessRefused(new IllegalArgumentException("bad amount")))
            .doesNotThrowAnyException();
        assertThatCode(() -> ListenerFailures.retryUnlessRefused(new DataIntegrityViolationException("duplicate")))
            .doesNotThrowAnyException();
        assertThatCode(() -> ListenerFailures.retryUnlessRefused(new PolicyNotFoundException()))
            .doesNotThrowAnyException();
    }

    @Test
    void aRefusalWrappedInAnotherFailureIsStillARefusal() {
        assertThatCode(() -> ListenerFailures.retryUnlessRefused(
            new RuntimeException("wrapped", new IllegalStateException("claim in the wrong state"))))
            .doesNotThrowAnyException();
    }

    @Test
    void anOutageOrABugIsThrownOnToBeDeliveredAgain() {
        CannotAcquireLockException lock = new CannotAcquireLockException("lock timeout");
        assertThatThrownBy(() -> ListenerFailures.retryUnlessRefused(lock)).isSameAs(lock);
        assertThatThrownBy(() -> ListenerFailures.retryUnlessRefused(
            new CannotCreateTransactionException("database unreachable"))).isInstanceOf(CannotCreateTransactionException.class);
        assertThatThrownBy(() -> ListenerFailures.retryUnlessRefused(new NullPointerException()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCheckedFailureIsThrownOnWrapped() {
        IOException io = new IOException("disk");
        assertThatThrownBy(() -> ListenerFailures.retryUnlessRefused(io)).isInstanceOf(IllegalStateException.class)
            .hasCause(io);
    }
}
