package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * The guide's posting modes (IFRS 17 posting guide 2.3): AUTO is posted only by the platform -- from events, its own
 * runs or the IFRS 17 engine -- and is locked to manual journals; MAN is posted by finance or actuarial staff; BOTH
 * is mostly automatic with authorised adjustments, which need a reason code.
 */
public enum PostingMode { AUTO, MAN, BOTH }
