package tz.co.nlolo.lifeplatform.underwriting.api;

/**
 * Where a case is. RETURNED (2026-10-20): sent back to the agent for amendment with what is wrong and what to change;
 * the agent's resubmission reopens the same case as OPEN.
 */
public enum UnderwritingCaseStatus { OPEN, IN_REVIEW, DECIDED, RETURNED }
