package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * A step of the expense allocation that the period, the allocation's state or its people refuse (409, IFRS 17 I5b): a
 * period not closing, a second allocation while one awaits a decision, a decision by its preparer, a total above the pool
 * not acknowledged, no group of insurance contracts to allocate to.
 */
public class ExpenseAllocationStateException extends RuntimeException {
    public ExpenseAllocationStateException(String message) {
        super(message);
    }
}
