package tz.co.nlolo.lifeplatform.distribution.api;

/** Review fix (Task 5): a raw {@code java.util.NoSuchElementException} for an unknown/cross-tenant
 * {@code statementId} would silently 500 at Task 9's HTTP boundary instead of 404ing, unless that
 * task happened to remember to add a handler for a bare JDK exception -- easy to miss, unlike a
 * named type. Same shape as {@link AgentNotFoundException}/{@link CommissionPlanNotFoundException},
 * the two lookups Task 3 anticipated; this is the third. */
public class CommissionStatementNotFoundException extends RuntimeException {
    public CommissionStatementNotFoundException(String message) { super(message); }
}
