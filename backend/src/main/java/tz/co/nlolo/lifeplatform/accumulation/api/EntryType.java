package tz.co.nlolo.lifeplatform.accumulation.api;

/** Every kind of financial transaction an account can carry. The database CHECK mirrors this list. */
public enum EntryType {
    CONTRIBUTION, TOP_UP, TRANSFER_IN, ALLOCATION_CHARGE, POLICY_FEE, INTEREST, WITHDRAWAL,
    SURRENDER, MATURITY, DEATH_CLAIM, FREE_LOOK_REFUND, ADJUSTMENT, REVERSAL
}
