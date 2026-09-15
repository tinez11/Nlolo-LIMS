package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.product.api.TiraFiling;

import java.time.LocalDate;

/**
 * One filing for every test that publishes a product version.
 *
 * <p>A fixed past date rather than {@code LocalDate.now()}: the filing is compared against today
 * only to refuse future dates, and a constant keeps every test independent of the clock.
 *
 * <p>Shared rather than repeated at ~103 call sites because none of those tests is about the
 * filing — they publish a version because they need one to exist. The tests that ARE about the
 * filing build their own.
 */
public final class ProductFilingFixture {

    public static final TiraFiling ANY_FILING =
        new TiraFiling("TIRA/TEST/0001", LocalDate.of(2020, 1, 1));

    private ProductFilingFixture() {}
}
