package tz.co.nlolo.lifeplatform.payment.application;

/**
 * The policy the payment request being handled is for (2026-10-27), held for the length of one event the way
 * {@code TenantContext} holds its tenant. The listener sets it from the request's payload and clears it after; the
 * rows it records take it, so the payments register can say which policy money moved for without every handler
 * threading one more argument through.
 */
final class RequestPolicy {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestPolicy() {}

    static void set(String policyNumber) {
        CURRENT.set(policyNumber);
    }

    static String current() {
        return CURRENT.get();
    }

    static void clear() {
        CURRENT.remove();
    }
}
