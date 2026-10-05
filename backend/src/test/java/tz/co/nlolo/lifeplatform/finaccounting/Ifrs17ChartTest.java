package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The guide's chart (2.5), exactly: codes, types, normal balances and modes, parents before children. */
class Ifrs17ChartTest {

    private final List<ChartOfAccountBlueprint.Seed> chart = ChartOfAccountBlueprint.accounts();
    private final Map<String, ChartOfAccountBlueprint.Seed> byCode =
        chart.stream().collect(Collectors.toMap(ChartOfAccountBlueprint.Seed::code, Function.identity()));

    @Test
    void everyParentComesBeforeItsChildrenAndSharesItsPrefix() {
        Set<String> seen = new HashSet<>();
        for (ChartOfAccountBlueprint.Seed s : chart) {
            if (s.parentCode() != null) {
                assertThat(seen).as("parent of %s", s.code()).contains(s.parentCode());
                assertThat(s.code()).startsWith(ChartOfAccount.significantPrefix(s.parentCode()));
            }
            assertThat(seen.add(s.code())).as("duplicate %s", s.code()).isTrue();
        }
        assertThat(chart.stream().filter(s -> s.parentCode() == null).map(ChartOfAccountBlueprint.Seed::code))
            .containsExactly("1000", "2000", "3000", "4000", "5000", "6000", "7000", "8000", "9000");
    }

    @Test
    void theGuidesAccountsCarryTheGuidesTreatment() {
        assertThat(byCode.get("2110").name()).isEqualTo("LRC – present value of future cash flows (PVFCF)");
        assertThat(byCode.get("2122").normalBalance()).isEqualTo(PostingDirection.DR);   // contra-liability
        assertThat(byCode.get("2122").type()).isEqualTo(AccountType.LIABILITY);
        assertThat(byCode.get("2121").mode()).isEqualTo(PostingMode.AUTO);
        assertThat(byCode.get("1110").mode()).isEqualTo(PostingMode.BOTH);
        assertThat(byCode.get("3110").mode()).isEqualTo(PostingMode.MAN);
        assertThat(byCode.get("9160").type()).isEqualTo(AccountType.CLEARING);
        assertThat(byCode.get("6120").type()).isEqualTo(AccountType.INCOME);
        assertThat(byCode.get("5410").normalBalance()).isEqualTo(PostingDirection.CR);  // contra-expense
        assertThat(byCode.get("3105").postingAllowed()).isFalse();                       // memorandum only
    }

    @Test
    void theChartHasEveryAccountOfTheGuide() {
        assertThat(chart.stream().filter(ChartOfAccountBlueprint.Seed::postingAllowed)).hasSize(203);
        assertThat(chart).hasSize(246);   // 203 posting accounts + 43 headings
    }

    @Test
    void aUsedForNoteMayContainSemicolons() {
        assertThat(byCode.get("9160").usedFor()).isEqualTo("Engine results must fully post; any balance means an unmapped line.");
    }
}
