package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Seeds the nine accounts M9 posts to (see {@link PostingRule#seedAccounts()}) for one tenant,
 * lazily, on first use. EVERY ACCOUNT IS A FINANCE SIGN-OFF PLACEHOLDER -- see V2 section 5 and
 * {@link PostingRule}'s class javadoc; no document on this platform specifies real account codes.
 *
 * <p>A real deployment does not call this at all: it seeds a real, Finance-approved chart of
 * accounts per tenant during onboarding. This seeder exists so M9's own tests and any lazily
 * initialised dev/test tenant have a chart to post against without a migration hardcoding a
 * tenant id (V2 section 8 deliberately leaves the seed INSERT out of the migration for exactly
 * that reason).
 */
@Component
public class ChartOfAccountSeeder {

    private final ChartOfAccountRepository chartOfAccountRepository;

    public ChartOfAccountSeeder(ChartOfAccountRepository chartOfAccountRepository) {
        this.chartOfAccountRepository = chartOfAccountRepository;
    }

    /** No-op if {@code tenantId} already has any account -- never double-seeds. */
    public void seedIfAbsent(UUID tenantId, String seededBy) {
        if (chartOfAccountRepository.existsByTenantId(tenantId)) {
            return;
        }
        for (Map.Entry<String, String> account : PostingRule.seedAccounts().entrySet()) {
            String accountCode = account.getKey();
            String name = account.getValue();
            chartOfAccountRepository.save(new ChartOfAccount(
                tenantId, accountCode, name,
                accountTypeFor(accountCode),
                PostingRule.normalBalanceFor(accountCode),
                seededBy));
        }
    }

    /** Derived from the account code's leading digit, per the conventional five-block scheme
     * documented in V2 section 5: 1-ASSET, 2-LIABILITY, 3-EQUITY, 4-INCOME, 5-EXPENSE. */
    private static AccountType accountTypeFor(String accountCode) {
        return switch (accountCode.charAt(0)) {
            case '1' -> AccountType.ASSET;
            case '2' -> AccountType.LIABILITY;
            case '3' -> AccountType.EQUITY;
            case '4' -> AccountType.INCOME;
            case '5' -> AccountType.EXPENSE;
            default -> throw new IllegalArgumentException("Unrecognised account code block: " + accountCode);
        };
    }
}
