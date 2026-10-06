package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Each group's ledger figures against the engine's closing ones (IFRS 17 I5a; guide 5.3, "ledger insurance contract
 * liability by group = engine closing balance"; user answer Q4). Both as booked, net Dr - Cr: a liability is negative.
 * A difference of up to TZS 1.00 is rounding (AGREED); more is an EXCEPTION. Pure.
 * <ul>
 *   <li>Policy group: LRC = every 21xx account except the 2190 reclass; LIC = 22xx; CSM = 2112.</li>
 *   <li>Reinsurance-held group: asset for remaining coverage (ARC) = 1410-1413; for incurred claims (AIC) = 1420;
 *       the reinsurance CSM = 1412.</li>
 * </ul>
 */
public final class EngineReconciler {

    public static final BigDecimal ROUNDING = new BigDecimal("1.00");

    /** One figure of one group: ledger, engine, ledger - engine, AGREED or EXCEPTION. */
    public record Row(String group, String figure, BigDecimal ledger, BigDecimal engine, BigDecimal difference,
                      String status) {}

    private EngineReconciler() {}

    public static List<Row> reconcile(String group, Map<String, BigDecimal> ledgerByAccount, EngineResults.Closing engine) {
        List<Row> rows = new ArrayList<>();
        if (EngineResults.isReinsurance(group)) {
            rows.add(row(group, "ARC", sum(ledgerByAccount, a -> a.compareTo("1410") >= 0 && a.compareTo("1413") <= 0), engine.arc()));
            rows.add(row(group, "AIC", sum(ledgerByAccount, "1420"::equals), engine.aic()));
            rows.add(row(group, "RI_CSM", sum(ledgerByAccount, "1412"::equals), engine.riCsm()));
        } else {
            rows.add(row(group, "LRC", sum(ledgerByAccount, a -> a.startsWith("21") && !a.equals("2190")), engine.lrc()));
            rows.add(row(group, "LIC", sum(ledgerByAccount, a -> a.startsWith("22")), engine.lic()));
            rows.add(row(group, "CSM", sum(ledgerByAccount, "2112"::equals), engine.csm()));
        }
        return rows;
    }

    private static Row row(String group, String figure, BigDecimal ledger, BigDecimal engine) {
        BigDecimal e = engine == null ? BigDecimal.ZERO : engine;
        BigDecimal difference = ledger.subtract(e);
        return new Row(group, figure, ledger, e, difference,
            difference.abs().compareTo(ROUNDING) <= 0 ? "AGREED" : "EXCEPTION");
    }

    private static BigDecimal sum(Map<String, BigDecimal> byAccount, Predicate<String> accounts) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Map.Entry<String, BigDecimal> e : byAccount.entrySet()) {
            if (accounts.test(e.getKey())) {
                total = total.add(e.getValue());
            }
        }
        return total;
    }
}
