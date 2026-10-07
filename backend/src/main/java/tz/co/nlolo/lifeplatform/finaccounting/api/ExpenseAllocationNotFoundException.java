package tz.co.nlolo.lifeplatform.finaccounting.api;

/** No expense allocation by that id for the current tenant (404, IFRS 17 I5b). */
public class ExpenseAllocationNotFoundException extends RuntimeException {
    public ExpenseAllocationNotFoundException(String message) {
        super(message);
    }
}
