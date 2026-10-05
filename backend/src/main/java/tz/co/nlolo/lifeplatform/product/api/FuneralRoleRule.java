package tz.co.nlolo.lifeplatform.product.api;

/**
 * Who a funeral version will cover in one role, and for how long.
 *
 * @param coverStopAge   the age cover ends for this role; null when it never stops for age
 * @param studentStopAge CHILD only: the age cover ends instead for a child marked as a student; null
 *                       when the version has no student extension
 */
public record FuneralRoleRule(FuneralRole role, int maxLives, int minEntryAge, int maxEntryAge,
                              Integer coverStopAge, Integer studentStopAge) {}
