package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineReconciler;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineResults;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineRunJournals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I5a: an engine run's journals through 9160, and the reconciliation of the ledger to the engine. */
class EngineRunMathTest {

    private static EngineResults.Line line(int row, String group, String account, String side, String amount) {
        return new EngineResults.Line(row, group, "P-08", account, side, new BigDecimal(amount), "CSM_REL", null);
    }

    @Test
    void eachEngineLineIsCounterPostedThrough9160WhichNetsToZeroPerGroup() {
        Map<String, List<EngineRunJournals.Leg>> legs = EngineRunJournals.legs(List.of(
            line(2, "TERM-GMM-2026-REM", "2112", "DR", "14000000.00"),
            line(3, "TERM-GMM-2026-REM", "4130", "CR", "14000000.00"),
            line(4, "RI-AB12CD34-2026", "1410", "DR", "480000.00"),
            line(5, "RI-AB12CD34-2026", "1436", "CR", "480000.00")));
        assertThat(legs).containsOnlyKeys("TERM-GMM-2026-REM", "RI-AB12CD34-2026");
        assertThat(legs.get("TERM-GMM-2026-REM")).extracting(l -> l.side() + " " + l.account())
            .containsExactly("DR 2112", "CR 9160", "CR 4130", "DR 9160");
        assertThat(EngineRunJournals.clearingNet(legs)).isZero();
        legs.values().forEach(group -> assertThat(EngineRunJournals.balanced(group)).isTrue());
    }

    @Test
    void anUnbalancedGroupLeaves9160NotAtZero() {
        Map<String, List<EngineRunJournals.Leg>> legs = EngineRunJournals.legs(List.of(
            line(2, "TERM-GMM-2026-REM", "2112", "DR", "10.00"),
            line(3, "TERM-GMM-2026-REM", "4130", "CR", "9.00")));
        assertThat(EngineRunJournals.clearingNet(legs)).isEqualByComparingTo("-1.00");
    }

    @Test
    void aPolicyGroupsFiguresAreReconciledWithinARoundingTzsOne() {
        Map<String, BigDecimal> ledger = Map.of("2110", new BigDecimal("150000000.00"), "2111", new BigDecimal("-30000000.00"),
            "2112", new BigDecimal("-106000000.00"), "2121", new BigDecimal("-400000.00"), "2190", new BigDecimal("999.00"),
            "2216", new BigDecimal("-4000000.00"), "4130", new BigDecimal("-14000000.00"));
        EngineResults.Closing engine = new EngineResults.Closing(2, "TERM-GMM-2026-REM", new BigDecimal("13599999.50"),
            new BigDecimal("-4000000.00"), new BigDecimal("-106000005.00"), null, null, null);
        List<EngineReconciler.Row> rows = EngineReconciler.reconcile("TERM-GMM-2026-REM", ledger, engine);
        assertThat(rows).extracting(r -> r.figure() + " " + r.ledger().toPlainString() + " " + r.status())
            .containsExactly("LRC 13600000.00 AGREED", "LIC -4000000.00 AGREED", "CSM -106000000.00 EXCEPTION");
        assertThat(rows.get(0).difference()).isEqualByComparingTo("0.50");
        assertThat(rows.get(2).difference()).isEqualByComparingTo("5.00");
    }

    @Test
    void aReinsuranceGroupIsReconciledOnItsAssetAccounts() {
        Map<String, BigDecimal> ledger = Map.of("1410", new BigDecimal("30000.00"), "1412", new BigDecimal("-50000.00"),
            "1420", new BigDecimal("1000000.00"), "6110", new BigDecimal("500000.00"));
        EngineResults.Closing engine = new EngineResults.Closing(3, "RI-AB12CD34-2026", null, null, null,
            new BigDecimal("-20000.00"), new BigDecimal("1000000.00"), new BigDecimal("-50001.00"));
        assertThat(EngineReconciler.reconcile("RI-AB12CD34-2026", ledger, engine))
            .extracting(r -> r.figure() + " " + r.ledger().toPlainString() + " " + r.status())
            .containsExactly("ARC -20000.00 AGREED", "AIC 1000000.00 AGREED", "RI_CSM -50000.00 AGREED");
    }
}
