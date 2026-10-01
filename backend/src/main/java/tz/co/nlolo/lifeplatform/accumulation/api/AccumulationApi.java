package tz.co.nlolo.lifeplatform.accumulation.api;

import java.util.List;
import java.util.Optional;

/**
 * The accumulation engine's published face (product step 3). It OWNS an account's balance and every
 * transaction that changes it: other modules ask through here or by event, and nothing else writes
 * the balance. Tenant from {@code TenantContext} throughout. Tasks 3-8 add the acting methods.
 */
public interface AccumulationApi {

    /** Empty for a policy on a SCALE version -- every policy sold before this step. */
    Optional<AccountView> findAccount(String policyNumber);

    boolean isAccount(String policyNumber);

    /** Every entry, in sequence order. Immutable history: corrections appear as REVERSAL entries. */
    List<LedgerEntryView> entries(String policyNumber);
}
