package tz.co.nlolo.lifeplatform.reporting.api;

/** AS_OF: a snapshot on one date. PERIOD: what happened between two dates. COHORT: policies activated in a
 * range of months, followed forward. AS_OF_AND_PERIOD: both a snapshot date and a period (clients). */
public enum DateFilterKind { AS_OF, PERIOD, COHORT, AS_OF_AND_PERIOD }
