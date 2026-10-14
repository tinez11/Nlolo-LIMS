package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerAccountsTest {

    private static AccountBalanceView account(String code, AccountType type, String own, String rolled) {
        return new AccountBalanceView(code, "Account " + code, type, PostingDirection.DR, null, (short) 1, true,
            AccountStatus.ACTIVE, "TZS", new BigDecimal(own), BigDecimal.ZERO, new BigDecimal(rolled),
            BigDecimal.ZERO, new BigDecimal(rolled));
    }

    @Test
    void anAccountsClassIsItsFirstDigitAndThreeZeroes() {
        assertEquals("1000", LedgerAccounts.classOf("1100"));
        assertEquals("8000", LedgerAccounts.classOf("8490"));
        assertEquals("9000", LedgerAccounts.classOf("9000"));
    }

    @Test
    void filteringKeepsOnlyTheClassAskedFor() {
        List<AccountBalanceView> all = List.of(
            account("1100", AccountType.ASSET, "10", "10"),
            account("1900", AccountType.ASSET, "15", "15"),
            account("2100", AccountType.LIABILITY, "20", "20"));

        List<AccountBalanceView> kept = LedgerAccounts.inClass(all, "1000");

        assertEquals(List.of("1100", "1900"), kept.stream().map(AccountBalanceView::accountCode).toList());
    }

    /** An untouched account is not a zero worth printing: a 246-account chart would bury the dozen that moved. */
    @Test
    void anAccountWithNoMovementIsNotPosted() {
        assertFalse(LedgerAccounts.posted(account("1100", AccountType.ASSET, "0", "0")));
        assertTrue(LedgerAccounts.posted(account("1100", AccountType.ASSET, "0", "5")));
        assertTrue(LedgerAccounts.posted(account("1100", AccountType.ASSET, "5", "5")));
    }

    @Test
    void everyClassRootIsNamed() {
        assertEquals("Assets", LedgerAccounts.CLASS_NAMES.get("1000"));
        assertEquals(9, LedgerAccounts.CLASS_NAMES.size());
        for (String classRoot : LedgerAccounts.BALANCE_SHEET_CLASSES) {
            assertTrue(LedgerAccounts.CLASS_NAMES.containsKey(classRoot), classRoot + " has no name");
        }
        for (String classRoot : LedgerAccounts.INCOME_STATEMENT_CLASSES) {
            assertTrue(LedgerAccounts.CLASS_NAMES.containsKey(classRoot), classRoot + " has no name");
        }
    }

    /**
     * Which classes are income decides the SIGN of the result. 7000 is the finance and investment result and
     * reads as income; 6000 is the reinsurance result and reads as an expense. Getting either backwards flips
     * a profit into a loss of exactly the same size, which is the kind of wrong that looks right.
     */
    @Test
    void theIncomeClassesAreTheRevenueAndTheFinanceResult() {
        assertTrue(LedgerAccounts.isIncomeClass("4000"));
        assertTrue(LedgerAccounts.isIncomeClass("7000"));
        assertFalse(LedgerAccounts.isIncomeClass("5000"));
        assertFalse(LedgerAccounts.isIncomeClass("6000"));
        assertFalse(LedgerAccounts.isIncomeClass("8000"));
    }

    /** The two statements must not overlap, or a figure would be reported twice. */
    @Test
    void noClassAppearsOnBothStatements() {
        assertTrue(LedgerAccounts.BALANCE_SHEET_CLASSES.stream()
            .noneMatch(LedgerAccounts.INCOME_STATEMENT_CLASSES::contains));
    }
}
