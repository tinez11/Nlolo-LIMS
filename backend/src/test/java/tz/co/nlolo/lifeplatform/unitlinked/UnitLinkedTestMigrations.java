package tz.co.nlolo.lifeplatform.unitlinked;

import tz.co.nlolo.lifeplatform.funeral.FuneralTestMigrations;

import java.util.stream.Stream;

/**
 * Every migration a unit-linked policy's path reaches: the funeral list (which already chains annuity, deposit,
 * accumulation, issuance, billing, the payment rail, claims and the ledger) plus the unit-linked ones. One list
 * for every unit-linked test class; each later task appends here.
 */
public final class UnitLinkedTestMigrations {
    private UnitLinkedTestMigrations() {}

    private static final String[] UNIT_LINKED = {
        "db-migrations/refdata/V7__unit_linked_price_move_alert.sql",
        "db-migrations/unitlinked/V1__create_unitlinked_schema.sql",
        "db-migrations/product/V25__unit_linked_terms.sql",
        // U2: switching, withdrawals, top-ups, the surrender charge; published terms made immutable.
        "db-migrations/product/V26__unit_linked_options.sql",
        "db-migrations/underwriting/V17__unit_linked_choice.sql",
        "db-migrations/unitlinked/V2__units.sql",
        "db-migrations/finaccounting/V9__unit_linked_accounts.sql",
        "db-migrations/payment/V13__unit_linked_purposes.sql",
        // A unit-linked surrender request carries no quoted value (plan R11): quoted_value_amount becomes nullable.
        "db-migrations/policy/V36__unit_linked_policy.sql",
        // U2: switches, withdrawals, top-ups, the split history, statements; the UL_TOP_UP collection purpose.
        "db-migrations/unitlinked/V3__u2.sql",
        "db-migrations/payment/V14__unit_linked_top_up.sql",
    };

    public static final String[] ALL = Stream.concat(Stream.of(FuneralTestMigrations.ALL), Stream.of(UNIT_LINKED))
        .toArray(String[]::new);
}
