package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * An accounting period's state (IFRS 17 spec §5.4): OPEN takes every posting; CLOSING takes no event postings while
 * the month-end steps run; LOCKED takes nothing until a second person approves reopening it.
 */
public enum PeriodStatus { OPEN, CLOSING, LOCKED }
