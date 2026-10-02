package tz.co.nlolo.lifeplatform.accumulation.api;

/** A deposit term's life: it runs, then matures, is terminated early, or is cancelled in the free look. */
public enum DepositPeriodStatus { RUNNING, MATURED, TERMINATED, CANCELLED }
