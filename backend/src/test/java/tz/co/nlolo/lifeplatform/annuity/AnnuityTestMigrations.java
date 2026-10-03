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
        // benefitpayout V2 and V3 arrive through DepositTestMigrations, which every benefitpayout class sweeps.
        "db-migrations/payment/V11__annuity_purpose.sql",
        "db-migrations/policy/V33__annuity_ended_status.sql",
        "db-migrations/claims/V1__create_claims_schema.sql",
        "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
        "db-migrations/claims/V3__registration_idempotency_key.sql",
        "db-migrations/claims/V5__claim_policy_member.sql",
        "db-migrations/claims/V6__exclusion_decline.sql",
        "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
        "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
        "db-migrations/claims/V9__zero_annuity_settlement.sql",
        "db-migrations/annuity/V1__create_annuity_schema.sql",
    };

    public static final String[] ALL = Stream.concat(Stream.of(DepositTestMigrations.ALL), Stream.of(ANNUITY))
        .toArray(String[]::new);
}
