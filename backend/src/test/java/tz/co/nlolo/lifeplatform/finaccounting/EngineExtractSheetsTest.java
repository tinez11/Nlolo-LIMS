package tz.co.nlolo.lifeplatform.finaccounting;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets.BalanceRow;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets.CashFlowRow;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets.PolicyRow;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IFRS 17 I5a: the extract the engine is sent (month-end step 6) -- three sheets, the groups they name, two formats. */
class EngineExtractSheetsTest {

    private static final EngineExtractSheets SHEETS = new EngineExtractSheets(
        List.of(new CashFlowRow("TERM-GMM-2026-REM", "PRM_REN", "2121", "CR", new BigDecimal("400000.00"), "TZS"),
            new CashFlowRow("RI-AB12CD34-2026", null, "1436", "DR", new BigDecimal("50000.00"), "TZS")),
        List.of(new BalanceRow("TERM-GMM-2026-REM", "2121", BigDecimal.ZERO, new BigDecimal("-400000.00"), "TZS"),
            new BalanceRow("FUN-PAA-2026-REM", "2141", new BigDecimal("-10.00"), new BigDecimal("-10.00"), "TZS")),
        List.of(new PolicyRow("POL-1", "TERM-GMM-2026-REM", "GMM", LocalDate.of(2026, 3, 1), new BigDecimal("2000000.00"),
            new BigDecimal("100000.00"), "MONTHLY", "ACTIVE", null, BigDecimal.ZERO, "TZS")));

    @Test
    void theGroupsAreEveryGroupAnySheetNames() {
        assertThat(SHEETS.groups()).containsExactly("FUN-PAA-2026-REM", "RI-AB12CD34-2026", "TERM-GMM-2026-REM");
    }

    @Test
    void eachSheetIsACsvWithItsOwnHeader() {
        assertThat(new String(SHEETS.csv("cash-flows"), StandardCharsets.UTF_8).lines().toList())
            .containsExactly("group,movement,account,side,amount,currency",
                "TERM-GMM-2026-REM,PRM_REN,2121,CR,400000.00,TZS",
                "RI-AB12CD34-2026,,1436,DR,50000.00,TZS");
        assertThat(new String(SHEETS.csv("balances"), StandardCharsets.UTF_8).lines().findFirst())
            .contains("group,account,opening_net_dr_minus_cr,closing_net_dr_minus_cr,currency");
        assertThat(new String(SHEETS.csv("policies"), StandardCharsets.UTF_8).lines().skip(1).findFirst())
            .contains("POL-1,TERM-GMM-2026-REM,GMM,2026-03-01,2000000.00,100000.00,MONTHLY,ACTIVE,,0,TZS");
        assertThatThrownBy(() -> SHEETS.csv("other")).hasMessageContaining("cash-flows, balances or policies");
    }

    @Test
    void theWorkbookHoldsTheThreeSheetsWithNumbersAsNumbers() throws Exception {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(SHEETS.xlsx("2026-10", 1)))) {
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("About");
            Sheet cash = wb.getSheet("Cash flows");
            assertThat(cash.getRow(0).getCell(0).getStringCellValue()).isEqualTo("group");
            assertThat(cash.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(400000.00);
            assertThat(wb.getSheet("Balances").getLastRowNum()).isEqualTo(2);
            assertThat(wb.getSheet("Policies").getRow(1).getCell(0).getStringCellValue()).isEqualTo("POL-1");
            assertThat(wb.getSheet("About").getRow(1).getCell(1).getStringCellValue()).isEqualTo("2026-10 #1");
        }
    }
}
