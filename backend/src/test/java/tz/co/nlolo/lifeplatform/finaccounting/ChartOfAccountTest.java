package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartOfAccountTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static ChartOfAccount receivables() {
        return ChartOfAccount.root(TENANT, "1200", "Receivables", false, "TZS", "test");
    }

    @Test
    void significantPrefixStripsTrailingZeros() {
        assertThat(ChartOfAccount.significantPrefix("1000")).isEqualTo("1");
        assertThat(ChartOfAccount.significantPrefix("1200")).isEqualTo("12");
        assertThat(ChartOfAccount.significantPrefix("1210")).isEqualTo("121");
        assertThat(ChartOfAccount.significantPrefix("1234")).isEqualTo("1234");
    }

    @Test
    void aRootIsLevelOneAndHasNoParent() {
        ChartOfAccount root = ChartOfAccount.root(TENANT, "1000", "Assets", false, "TZS", "test");
        assertThat(root.getLevel()).isEqualTo((short) 1);
        assertThat(root.getParentCode()).isNull();
        assertThat(root.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(root.getCurrency()).isEqualTo("TZS");
    }

    @Test
    void aChildIsOneLevelDeeperThanItsParent() {
        ChartOfAccount child = ChartOfAccount.childOf(
            receivables(), "1210", "Premium Receivables", true, "TZS", "BILLING", null, "test");
        assertThat(child.getLevel()).isEqualTo((short) 2);
        assertThat(child.getParentCode()).isEqualTo("1200");
        assertThat(child.getControlOf()).isEqualTo("BILLING");
        assertThat(child.getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void aRootTakesItsClassAndAChildInheritsItsParentsTreatment() {
        ChartOfAccount child = ChartOfAccount.childOf(
            receivables(), "1210", "Premium Receivables", true, "TZS", null, null, "test");
        assertThat(child.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(child.getNormalBalance()).isEqualTo(PostingDirection.DR);
        assertThat(child.getMode()).isEqualTo(PostingMode.MAN);       // hand-added: staff-posted until finance says otherwise

        ChartOfAccount liability = ChartOfAccount.root(TENANT, "2000", "Liabilities", false, "TZS", "test");
        assertThat(liability.getAccountType()).isEqualTo(AccountType.LIABILITY);
        assertThat(liability.getNormalBalance()).isEqualTo(PostingDirection.CR);
        ChartOfAccount clearing = ChartOfAccount.root(TENANT, "9000", "Clearing", false, "TZS", "test");
        assertThat(clearing.getAccountType()).isEqualTo(AccountType.CLEARING);
        ChartOfAccount reinsurance = ChartOfAccount.root(TENANT, "6000", "Reinsurance held", false, "TZS", "test");
        assertThat(reinsurance.getAccountType()).isEqualTo(AccountType.EXPENSE);
    }

    @Test
    void aSeededContraAccountKeepsTheGuidesBalanceAndMode() {
        // 2122 Premiums due is a contra-liability: a LIABILITY with a DEBIT normal balance, posted only by the system
        // (IFRS 17 I1, R4) -- something the leading digit alone would have got wrong.
        ChartOfAccount premiumsDue = ChartOfAccount.seeded(TENANT, ChartOfAccountBlueprint.accounts().stream()
            .filter(s -> s.code().equals("2122")).findFirst().orElseThrow(), null, "TZS", "test");
        assertThat(premiumsDue.getAccountType()).isEqualTo(AccountType.LIABILITY);
        assertThat(premiumsDue.getNormalBalance()).isEqualTo(PostingDirection.DR);
        assertThat(premiumsDue.getMode()).isEqualTo(PostingMode.AUTO);
        assertThat(premiumsDue.getDescription()).startsWith("Debit sub-account");
    }

    @Test
    void aChildOutsideItsParentsBlockIsRejected() {
        assertThatThrownBy(() -> ChartOfAccount.childOf(
                receivables(), "2110", "Claims Payable", true, "TZS", null, null, "test"))
            .isInstanceOf(FinaccountingValidationException.class)
            .hasMessageContaining("2110")
            .hasMessageContaining("1200");
    }

    @Test
    void anAccountMayNotBeItsOwnChild() {
        assertThatThrownBy(() -> ChartOfAccount.childOf(
                receivables(), "1200", "Receivables again", true, "TZS", null, null, "test"))
            .isInstanceOf(FinaccountingValidationException.class);
    }

    /** A skipped level is a flat branch, not an inconsistency -- the prefix rule prevents
     *  CONTRADICTION, and deliberately not this. */
    @Test
    void aChildMaySkipALevelAsLongAsItStaysInsideTheBlock() {
        ChartOfAccount assets = ChartOfAccount.root(TENANT, "1000", "Assets", false, "TZS", "test");
        ChartOfAccount skipped = ChartOfAccount.childOf(
            assets, "1110", "Main Bank Account", true, "TZS", null, null, "test");
        assertThat(skipped.getLevel()).isEqualTo((short) 2);
    }

    @Test
    void becomingAHeaderRevokesPosting() {
        ChartOfAccount leaf = ChartOfAccount.root(TENANT, "1300", "Investments", true, "TZS", "test");
        assertThat(leaf.isPostingAllowed()).isTrue();
        leaf.becomeHeader("test");
        assertThat(leaf.isPostingAllowed()).isFalse();
        assertThat(leaf.getUpdatedBy()).isEqualTo("test");
        assertThat(leaf.getUpdatedAt()).isNotNull();
    }

    @Test
    void deactivateAndActivateFlipStatus() {
        ChartOfAccount account = ChartOfAccount.root(TENANT, "1300", "Investments", true, "TZS", "test");
        account.deactivate("retiring");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(account.getUpdatedBy()).isEqualTo("retiring");

        account.activate("reinstating");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getUpdatedBy()).isEqualTo("reinstating");
    }

    @Test
    void anInactiveHeaderRefusesPostingForBothReasonsIndependently() {
        ChartOfAccount header = ChartOfAccount.root(TENANT, "1000", "Assets", false, "TZS", "test");
        assertThat(header.acceptsPostings()).isFalse();

        ChartOfAccount inactiveLeaf = ChartOfAccount.root(TENANT, "1300", "Investments", true, "TZS", "test");
        assertThat(inactiveLeaf.acceptsPostings()).isTrue();
        inactiveLeaf.deactivate("test");
        assertThat(inactiveLeaf.acceptsPostings()).isFalse();
    }

    @Test
    void describeSetsTheDescriptionAndStampsTheEditor() {
        ChartOfAccount account = ChartOfAccount.root(TENANT, "1300", "Investments", true, "TZS", "test");
        account.describe("Long-term holdings", "editor");
        assertThat(account.getDescription()).isEqualTo("Long-term holdings");
        assertThat(account.getUpdatedBy()).isEqualTo("editor");
    }
}
