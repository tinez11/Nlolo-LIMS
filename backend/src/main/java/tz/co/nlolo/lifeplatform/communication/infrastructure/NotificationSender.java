package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;

/**
 * The one way out of this module.
 *
 * <p>A port with two implementations rather than two injected clients, because the caller must
 * not care which channel it is using: {@code NotificationApiImpl} decides SMS or email from what
 * the party actually has on file, and a channel with no adapter is a configuration fact rather
 * than a branch in business logic.
 *
 * <p><b>A delivery failure is a return value here, never an exception.</b> Every caller of this
 * port is downstream of an {@code AFTER_COMMIT} listener reacting to something that already
 * happened — a policy was issued, a premium cleared. An unreachable SMS aggregator must leave a
 * FAILED dispatch row somebody can act on; it must not throw into a transaction whose business
 * fact is already durable. The same reasoning {@code MobileMoneyGatewayAdapter} applies to a
 * money rail, with the stakes reversed: there, a lost response might mean money moved, so it
 * raises. Here the worst case is a message nobody received, and recording that plainly beats
 * unwinding anything.
 */
public interface NotificationSender {

    /** Whether this adapter is the one that handles the channel. */
    boolean supports(NotificationChannel channel);

    /**
     * @param destination a phone number for SMS, an email address for EMAIL — already resolved
     *     and non-blank; this port does not know how to find a party.
     * @param body the fully rendered message. No placeholders remain by the time it gets here.
     * @return whether it left the platform, and if not, why not, in words fit for an operator
     *     reading an outbox row.
     */
    SendResult send(String destination, String body);

    /**
     * Left the platform, or did not and why.
     *
     * <p>"Left the platform" is the honest ceiling on what an adapter can claim. Neither SMTP nor
     * an aggregator's HTTP 200 proves a human received anything, and a delivery receipt is a
     * separate inbound story this project does not build. The outbox says SENT, which means
     * accepted by the transport, and nothing stronger.
     */
    record SendResult(boolean sent, String detail, boolean notSent) {

        public SendResult(boolean sent, String detail) {
            this(sent, detail, false);
        }

        public static SendResult ok() {
            return new SendResult(true, null);
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, detail);
        }

        /**
         * Deliberately not sent (2026-11-08): the channel is switched off, or the recipient is outside a testing
         * allowlist. A setting, not a fault -- recorded NOT_SENT, so FAILED means something worth acting on.
         */
        public static SendResult notSent(String detail) {
            return new SendResult(false, detail, true);
        }
    }

    /**
     * How the channel is set up (2026-11-08), for the console's one honest line about whether customers are reached:
     * LIVE (real recipients), LIMITED (live, but only to an allowlist), TEST (a local test mailbox or mock), OFF.
     */
    enum Mode { LIVE, LIMITED, TEST, OFF }

    /** This channel's mode; LIVE unless an adapter says otherwise. */
    default Mode mode() {
        return Mode.LIVE;
    }
}
