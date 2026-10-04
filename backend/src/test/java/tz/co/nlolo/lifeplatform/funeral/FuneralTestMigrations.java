package tz.co.nlolo.lifeplatform.funeral;

import tz.co.nlolo.lifeplatform.annuity.AnnuityTestMigrations;

import java.util.stream.Stream;

/**
 * Every migration a funeral policy's path reaches: the annuity list (issuance, billing, the payment rail,
 * claims V1-V9) plus the funeral ones. One list for every funeral test class; each later task appends here.
 */
public final class FuneralTestMigrations {
    private FuneralTestMigrations() {}

    private static final String[] FUNERAL = {
        "db-migrations/product/V24__funeral_terms.sql",
        "db-migrations/underwriting/V16__funeral_application.sql",
    };

    public static final String[] ALL = Stream.concat(Stream.of(AnnuityTestMigrations.ALL), Stream.of(FUNERAL))
        .toArray(String[]::new);
}
