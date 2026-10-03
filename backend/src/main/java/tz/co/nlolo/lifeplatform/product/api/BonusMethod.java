package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a reversionary bonus is computed (product step 4, Q1). SIMPLE: rate x sum assured. COMPOUND:
 * rate x (sum assured + bonuses already attached) -- so earlier bonuses earn bonus too.
 */
public enum BonusMethod { SIMPLE, COMPOUND }
