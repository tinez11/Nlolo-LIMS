package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One cell of an annuity form's grid: annual income per 1,000 of purchase price.
 *
 * @param sex               FEMALE or MALE on a BY_SEX form; null on UNISEX
 * @param age               the annuitant's age at purchase (last birthday)
 * @param ageDifferenceFrom joint forms only, inclusive: annuitant age minus joint-life age, negative
 *                          when the joint life is older; null on a single-life form
 * @param ageDifferenceTo   joint forms only, inclusive
 */
public record AnnuityRateRow(String sex, int age, Integer ageDifferenceFrom, Integer ageDifferenceTo,
                             BigDecimal annualRatePerMille) {}
