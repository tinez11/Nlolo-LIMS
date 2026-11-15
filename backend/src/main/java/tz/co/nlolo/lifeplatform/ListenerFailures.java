package tz.co.nlolo.lifeplatform;

/**
 * What an after-commit listener does with a failure (2026-11-14, durable events). The event publication registry
 * delivers an event again only when its listener throws; a listener that logs and returns has, as far as the registry
 * knows, finished. So a listener logs as it always has, then hands the failure here:
 *
 * <ul>
 *   <li><b>A refusal</b> -- a domain rule said no (a policy already active, a claim in the wrong state, a duplicate row,
 *       a bad argument). Delivering it again would be refused again, every ten minutes, for ever. Logged, and the
 *       delivery counts as done, as before.</li>
 *   <li><b>Anything else</b> -- the database unreachable, a lock timeout, a bug. Thrown on, so the delivery stays open
 *       and is delivered again: an outage heals itself, and a bug, once fixed, is caught up.</li>
 * </ul>
 *
 * <p>Only for listeners whose work runs in a transaction of its own, so a failed attempt left nothing behind, and
 * whose effects are safe to repeat. Throwing from an after-commit listener never reaches the request that raised the
 * event: Spring logs it at the end of that transaction, which has already committed.
 */
public final class ListenerFailures {

    private ListenerFailures() {}

    public static void retryUnlessRefused(Exception failure) {
        if (isRefusal(failure)) {
            return;
        }
        throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
    }

    static boolean isRefusal(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException
                    || t instanceof org.springframework.dao.DataIntegrityViolationException) {
                return true;
            }
            String name = t.getClass().getSimpleName();
            if (name.endsWith("StateException") || name.endsWith("NotEligibleException")
                    || name.endsWith("ValidationException") || name.endsWith("NotFoundException")
                    || name.endsWith("RefusedException") || name.endsWith("PlaceholderException")) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }
}
