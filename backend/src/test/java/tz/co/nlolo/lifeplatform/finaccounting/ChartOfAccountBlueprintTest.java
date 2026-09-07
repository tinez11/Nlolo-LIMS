package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint.Seed;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The blueprint is the single definition of this platform's chart of accounts, so these are
 * structural invariants rather than examples: anything asserted here is something the seeder,
 * the V5 migration and the posting guard all rely on being true.
 */
class ChartOfAccountBlueprintTest {

    @Test
    void seedsThirtySixAccounts() {
        assertThat(ChartOfAccountBlueprint.accounts()).hasSize(36);
    }

    @Test
    void everyParentAppearsBeforeItsChildren() {
        Set<String> seen = new HashSet<>();
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (seed.parentCode() != null) {
                assertThat(seen)
                    .as("parent %s of %s must be inserted first", seed.parentCode(), seed.code())
                    .contains(seed.parentCode());
            }
            seen.add(seed.code());
        }
    }

    @Test
    void everyParentCodeNamesARealAccount() {
        Set<String> codes = ChartOfAccountBlueprint.accounts().stream()
            .map(Seed::code).collect(Collectors.toSet());
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (seed.parentCode() != null) {
                assertThat(codes).as("%s names parent %s", seed.code(), seed.parentCode())
                    .contains(seed.parentCode());
            }
        }
    }

    @Test
    void everyChildCodeSitsInsideItsParentsBlock() {
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (seed.parentCode() == null) continue;
            String prefix = ChartOfAccount.significantPrefix(seed.parentCode());
            assertThat(seed.code())
                .as("%s must sit inside %s's block", seed.code(), seed.parentCode())
                .startsWith(prefix);
        }
    }

    @Test
    void noAccountWithChildrenAllowsPosting() {
        Set<String> parents = ChartOfAccountBlueprint.accounts().stream()
            .map(Seed::parentCode).filter(Objects::nonNull).collect(Collectors.toSet());
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (parents.contains(seed.code())) {
                assertThat(seed.postingAllowed())
                    .as("%s has children and must be a header", seed.code()).isFalse();
            }
        }
    }

    /** Every code PostingRule posts to must exist in the blueprint and accept postings --
     *  this is what fk_gl_posting_account_code enforces at runtime, asserted here at build time. */
    @Test
    void everyPostingRuleTargetIsAPostableBlueprintAccount() {
        Set<String> postable = ChartOfAccountBlueprint.accounts().stream()
            .filter(Seed::postingAllowed).map(Seed::code).collect(Collectors.toSet());
        assertThat(postable).contains(
            PostingRule.CASH, PostingRule.PREMIUM_RECEIVABLE, PostingRule.REINSURANCE_RECOVERABLE,
            PostingRule.POLICY_LOAN_RECEIVABLE, PostingRule.UNEARNED_PREMIUM,
            PostingRule.REINSURANCE_PAYABLE, PostingRule.CLAIMS_EXPENSE,
            PostingRule.COMMISSION_EXPENSE, PostingRule.REINSURANCE_CEDED_PREMIUM);
    }

    @Test
    void everyRemapTargetExistsInTheBlueprint() {
        Set<String> codes = ChartOfAccountBlueprint.accounts().stream()
            .map(Seed::code).collect(Collectors.toSet());
        assertThat(codes).containsAll(ChartOfAccountBlueprint.legacyRemap().values());
    }

    /** The one legacy code deliberately absent from the new chart, so V5's DELETE has
     *  something to prove it ran. */
    @Test
    void theLegacyPolicyLoanCodeIsNotReusedInTheNewChart() {
        Set<String> codes = ChartOfAccountBlueprint.accounts().stream()
            .map(Seed::code).collect(Collectors.toSet());
        assertThat(codes).doesNotContain("1400");
    }
}
