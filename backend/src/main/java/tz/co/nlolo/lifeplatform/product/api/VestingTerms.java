package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * A deferred annuity's vesting terms (product step 5, D2): the window inside which staff may vest
 * early or defer, what it vests into when nobody says otherwise, the lump-sum cap, and whether it is
 * locked before it vests. Always read from the version the policy was SOLD on; the annuity terms it
 * vests into are the product's CURRENT version's (spec Q7).
 *
 * @param surrenderBeforeVesting null only on an incoming request the validator refuses -- the version
 *                               must say (spec Q9)
 */
public record VestingTerms(int minVestingAge, int maxVestingAge, String defaultFormCode, String defaultFrequency,
                           BigDecimal maxCommutationPercent, Boolean surrenderBeforeVesting) {}
