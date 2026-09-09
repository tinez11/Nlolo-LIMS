package tz.co.nlolo.lifeplatform.communication.api;

/**
 * A way of reaching a customer that this platform can actually use.
 *
 * <p>Two values, not the four {@code communication.notification_template}'s CHECK constraint
 * allows. USSD and PUSH are in the schema because Deliverable 3 anticipated them; neither has a
 * transport behind it, and an enum constant with no adapter is a promise the code cannot keep —
 * it would compile, pass a template lookup, and then fail at send time with nothing useful to
 * say. They belong here when something can carry them.
 *
 * <p>SMS first because that is how most customers here are reachable. EMAIL is additional rather
 * than alternative: a party with both gets both, since an address on file is a second chance at
 * telling somebody their cover has not started, not a reason to skip the channel they actually
 * read.
 */
public enum NotificationChannel {
    SMS,
    EMAIL
}
