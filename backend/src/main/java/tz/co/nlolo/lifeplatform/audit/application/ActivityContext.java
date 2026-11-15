package tz.co.nlolo.lifeplatform.audit.application;

import java.util.UUID;

/**
 * The action a request is, while it runs (2026-11-14, activity log). Set by the recording filter around the request;
 * read by the audit listener, which records each event the request raised after commit on the same thread, so the
 * event names the action that caused it.
 */
public final class ActivityContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private ActivityContext() {}

    public static void set(UUID actionId) {
        CURRENT.set(actionId);
    }

    /** The current action, or null outside a person's request (a scheduled run, a redelivered event). */
    public static UUID current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
