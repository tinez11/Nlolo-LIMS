package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a purchase is priced on. Sexes are the party's own words (FEMALE / MALE), null when not
 * recorded; the joint life's fields are null on a single-life form. pricingDate is the civil date the
 * rate is taken on -- the collection date at the lock, today for a quote.
 */
public record AnnuityPricingInput(String formCode, String frequency, BigDecimal purchasePrice,
                                  LocalDate annuitantDateOfBirth, String annuitantSex,
                                  LocalDate jointDateOfBirth, String jointSex, LocalDate pricingDate) {}
