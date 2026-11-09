package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement tree (2026-11-09, the reports redesign): a line is accounts or other lines, signed, evaluated on two
 * ledgers -- this period and the one it is compared with -- and every line can state the sum it is made of.
 */
class StatementTest {

    private static AccountBalanceView acct(String code, String balance) {
        BigDecimal b = new BigDecimal(balance);
        return new AccountBalanceView(code, "Account " + code, AccountType.INCOME, PostingDirection.CR, null, (short) 1,
            true, AccountStatus.ACTIVE, "TZS", BigDecimal.ZERO, b, BigDecimal.ZERO, b, b);
    }

    private static final List<Statement.Line> LINES = List.of(
        Statement.Line.accounts("revenue", "Insurance revenue", "UP", "4000"),
        Statement.Line.accounts("expenses", "Insurance service expenses", "DOWN", "5000"),
        Statement.Line.subtotal("result", "Insurance service result", "UP",
            new Statement.Part("line:revenue", 1), new Statement.Part("line:expenses", -1)));

    @Test
    void aSubtotalIsItsSignedPartsOnBothPeriods() {
        Map<String, Statement.Figures> f = Statement.evaluate(LINES,
            List.of(acct("4000", "1240.50"), acct("5000", "720.20")),
            List.of(acct("4000", "1168.10"), acct("5000", "667.80")));

        assertThat(f.get("result").current()).isEqualByComparingTo("520.30");
        assertThat(f.get("result").prior()).isEqualByComparingTo("500.30");
        assertThat(f.get("revenue").change()).isEqualByComparingTo("72.40");
        assertThat(f.get("revenue").percent()).isEqualTo("6.2");
    }

    @Test
    void aPriorOfZeroHasNoPercentAndNoComparisonHasNoPrior() {
        Map<String, Statement.Figures> f = Statement.evaluate(LINES, List.of(acct("4000", "10.00")),
            List.of(acct("4000", "0.00")));
        assertThat(f.get("revenue").percent()).isNull();

        Map<String, Statement.Figures> alone = Statement.evaluate(LINES, List.of(acct("4000", "10.00")), null);
        assertThat(alone.get("revenue").prior()).isNull();
        assertThat(alone.get("revenue").change()).isNull();
    }

    @Test
    void theCheckStatesTheSumInWordsAndFigures() {
        assertThat(Statement.check(List.of(new BigDecimal("1102.00"), new BigDecimal("96.30"), new BigDecimal("42.20")),
            List.of(1, 1, 1), new BigDecimal("1240.50")))
            .isEqualTo("1,102.00 + 96.30 + 42.20 = 1,240.50");
        assertThat(Statement.check(List.of(new BigDecimal("1240.50"), new BigDecimal("720.20")), List.of(1, -1),
            new BigDecimal("520.30")))
            .isEqualTo("1,240.50 − 720.20 = 520.30");
    }
}
