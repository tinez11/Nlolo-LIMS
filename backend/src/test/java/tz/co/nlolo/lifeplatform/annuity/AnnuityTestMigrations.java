package tz.co.nlolo.lifeplatform.annuity;

import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;

import java.util.stream.Stream;

/**
 * Every migration an annuity's path reaches (product step 5): the deposit list -- issuance,
 * billing's single premium, the payment rail -- plus the annuity's own. One list for every annuity
 * test class, so the next migration is added once. Each later D1 task appends its own here.
 */
public final class AnnuityTestMigrations {
    private AnnuityTestMigrations() {}

    private static final String[] ANNUITY = {
        "db-migrations/product/V22__annuity_terms.sql",
        "db-migrations/underwriting/V14__annuity_choice.sql",
    };

    public static final String[] ALL = Stream.concat(Stream.of(DepositTestMigrations.ALL), Stream.of(ANNUITY))
        .toArray(String[]::new);
}
