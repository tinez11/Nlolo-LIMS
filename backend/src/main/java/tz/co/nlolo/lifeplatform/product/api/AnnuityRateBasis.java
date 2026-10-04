package tz.co.nlolo.lifeplatform.product.api;

/**
 * Whether an annuity form's rates differ by sex (product step 5, Q4). A BY_SEX form refuses to price
 * a life whose sex is not recorded, as premium pricing refuses.
 */
public enum AnnuityRateBasis { UNISEX, BY_SEX }
