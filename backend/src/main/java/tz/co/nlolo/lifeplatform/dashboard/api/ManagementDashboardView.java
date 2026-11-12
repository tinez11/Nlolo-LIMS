package tz.co.nlolo.lifeplatform.dashboard.api;

import tz.co.nlolo.lifeplatform.reporting.api.CollectionFigures;

/**
 * The management overview (2026-11-12, analytics plan 5, D2): how the business is doing, each figure said once and
 * each read from the report or dashboard that owns it. Any field may be null: a source that could not be read shows
 * "Not available", never zero.
 *
 * @param policiesInForce on the day asked, from the Policies in force report
 * @param lapsed          lapsed in the period, from the same report's movement
 * @param openClaims      open on the day asked, from Claims in progress
 * @param casesToDecide   proposals not yet decided (open or in review), now
 * @param claimsToDecide  claims registered, under assessment or reopened, now
 */
public record ManagementDashboardView(
    Period period,
    Money collected, Money collectedBefore, String collectedChangePercent,
    Long newPolicies, Long newPoliciesBefore, String newPoliciesChangePercent,
    Long policiesInForce,
    CollectionFigures collections,
    Long lapsed,
    Money claimsPaid, Long openClaims,
    Long casesToDecide, Long claimsToDecide) {}
