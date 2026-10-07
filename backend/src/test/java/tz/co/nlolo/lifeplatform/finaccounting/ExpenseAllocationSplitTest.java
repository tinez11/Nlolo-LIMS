package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ExpenseAllocationSplit;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ExpenseAllocationSplit.Group;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ExpenseAllocationSplit.Line;
import tz.co.nlolo.lifeplatform.finaccounting.domain.MovementTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I5b: P-19's totals spread over the groups by driver, to the cent. */
class ExpenseAllocationSplitTest {

    private static final Group TERM = new Group("TERM-GMM-2026-REM", "GMM", 2, 1, 0);
    private static final Group FUN = new Group("FUN-PAA-2026-REM", "PAA", 1, 0, 1);
    private static final Group SAV = new Group("SAV-IFRS9-2026-REM", "IFRS9", 5, 5, 5);

    private static BigDecimal sum(List<Line> lines, String category) {
        return lines.stream().filter(l -> l.category().equals(category)).map(Line::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void eachTotalIsSharedByItsDriverToTheCentAndOnlyOverInsuranceGroups() {
        List<Line> lines = ExpenseAllocationSplit.split(new BigDecimal("100.00"), new BigDecimal("50.00"),
            new BigDecimal("30.00"), List.of(TERM, FUN, SAV), Set.of("PAA"));
        assertThat(lines).noneMatch(l -> l.group().equals(SAV.key()));
        assertThat(lines).filteredOn(l -> l.category().equals("MAINTENANCE"))
            .extracting(l -> l.group() + " " + l.account() + " " + l.amount() + " " + l.driver() + " " + l.driverCount())
            .containsExactly("FUN-PAA-2026-REM 5210 33.33 IN_FORCE 1", "TERM-GMM-2026-REM 5210 66.67 IN_FORCE 2");
        assertThat(lines).filteredOn(l -> l.category().equals("CLAIMS_HANDLING"))
            .extracting(l -> l.group() + " " + l.account() + " " + l.amount())
            .containsExactly("TERM-GMM-2026-REM 5215 50.00");
        assertThat(lines).filteredOn(l -> l.category().equals("ACQUISITION"))
            .extracting(l -> l.group() + " " + l.account() + " " + l.amount() + " " + l.driver())
            .containsExactly("FUN-PAA-2026-REM 5310 30.00 ISSUED");
        assertThat(sum(lines, "MAINTENANCE")).isEqualByComparingTo("100.00");
    }

    @Test
    void theLeftoverCentsGoToTheLargestRemaindersThenByGroupOrder() {
        assertThat(ExpenseAllocationSplit.share(new BigDecimal("0.10"), List.of(1L, 1L, 1L)))
            .extracting(BigDecimal::toPlainString).containsExactly("0.04", "0.03", "0.03");
        assertThat(ExpenseAllocationSplit.share(new BigDecimal("10.00"), List.of(1L, 2L)))
            .extracting(BigDecimal::toPlainString).containsExactly("3.33", "6.67");
    }

    @Test
    void aDriverAtZeroEverywhereFallsBackToInForceThenToEqualShares() {
        Group a = new Group("A-GMM-2026-REM", "GMM", 0, 0, 0);
        Group b = new Group("B-GMM-2026-REM", "GMM", 0, 0, 0);
        List<Line> lines = ExpenseAllocationSplit.split(BigDecimal.ZERO, new BigDecimal("1.00"), BigDecimal.ZERO,
            List.of(a, b), Set.of());
        assertThat(lines).extracting(l -> l.driver() + " " + l.amount()).containsExactly("EQUAL 0.50", "EQUAL 0.50");
        List<Line> fallback = ExpenseAllocationSplit.split(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4.00"),
            List.of(TERM), Set.of());
        assertThat(fallback).extracting(Line::driver).containsExactly("IN_FORCE");
    }

    @Test
    void acquisitionGoesTo5310OnlyForPaaExpensedWhenIncurred() {
        assertThat(ExpenseAllocationSplit.acquisitionAccount("PAA", true)).isEqualTo("5310");
        assertThat(ExpenseAllocationSplit.acquisitionAccount("PAA", false)).isEqualTo("2123");
        assertThat(ExpenseAllocationSplit.acquisitionAccount("GMM", true)).isEqualTo("2123");
        assertThat(ExpenseAllocationSplit.acquisitionAccount("VFA", false)).isEqualTo("2123");
    }

    @Test
    void zeroTotalsWriteNoLinesAndExpAcqIsAKnownMovement() {
        assertThat(ExpenseAllocationSplit.split(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(TERM), Set.of()))
            .isEmpty();
        assertThat(MovementTypes.CODES).contains("EXP_ACQ");
    }
}
