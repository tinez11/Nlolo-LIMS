package tz.co.nlolo.lifeplatform.reporting.api;

/**
 * AS_OF: a snapshot on one date. PERIOD: what happened between two dates. COHORT: policies activated in a
 * range of months, followed forward. AS_OF_AND_PERIOD: both a snapshot date and a period (clients).
 *
 * <p>MONTH and MONTH_RANGE: one accounting period, or a span of them. The ledger is organised by {@code
 * YYYY-MM} and carries no posting date of its own, so an accounting report that accepted a day would be
 * promising a precision the ledger cannot deliver.
 */
public enum DateFilterKind { AS_OF, PERIOD, COHORT, AS_OF_AND_PERIOD, MONTH, MONTH_RANGE }
