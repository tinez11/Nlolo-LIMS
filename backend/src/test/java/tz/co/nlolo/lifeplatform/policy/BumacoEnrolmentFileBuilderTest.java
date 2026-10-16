package tz.co.nlolo.lifeplatform.policy;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turns Bumaco's own August sheet into a file this platform will actually accept, and writes it to
 * the Desktop.
 *
 * <p><b>Why a conversion is needed at all.</b> Their sheet carries every required fact -- name,
 * date of birth, disbursed amount, term, disbursed date -- under their own column names, and the
 * enrolment parser does no aliasing: it strips and lower-cases a header and nothing more, so
 * {@code CLIENT NAME} never becomes {@code borrower_full_name} and the file is refused on a
 * missing required column. Their header is also not on row 1, which the parser requires.
 *
 * <p><b>Dates are written as TEXT in ISO form.</b> This is the whole point of doing it with POI
 * rather than by hand in Excel: an Excel serial such as {@code 46203} is refused by name, and so
 * is {@code 1-Sep-00}, because formatting the column as a date is exactly what produces it. POI
 * reads the real date behind the cell and this writes back the ISO string.
 *
 * <p>Skipped when the source sheet is absent -- it lives in a gitignored folder of real client
 * data, so this cannot run on a machine that does not have it.
 */
class BumacoEnrolmentFileBuilderTest {

    private static final Path SOURCE =
        Path.of("..", "sample data", "01TEMPLATE LIFE BUMACO AUGUST.xlsx");
    private static final Path OUT =
        Path.of(System.getProperty("user.home"), "Desktop", "bumaco-enrolment-ready.xlsx");

    /** The platform's columns, in the order its own template emits them. */
    private static final List<String> TEMPLATE_COLUMNS = List.of(
        "member_reference", "borrower_full_name", "borrower_date_of_birth", "borrower_sex",
        "borrower_national_id", "borrower_phone", "loan_principal_amount", "loan_term_months",
        "disbursement_date", "loan_account_number", "lender_premium_amount");

    /** Bumaco's column names, lower-cased, mapped to ours. Their sheet also carries S/N and AGE, which we drop. */
    private static final Map<String, String> THEIRS_TO_OURS = Map.of(
        "client name", "borrower_full_name",
        "gender", "borrower_sex",
        "date of birth", "borrower_date_of_birth",
        "disbursed date", "disbursement_date",
        "disbursed amount", "loan_principal_amount",
        "term", "loan_term_months",
        "premium", "lender_premium_amount");

    static boolean sourceSheetExists() {
        return Files.exists(SOURCE);
    }

    @Test
    @EnabledIf("sourceSheetExists")
    void buildsAnUploadableFileFromBumacosOwnSheet() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();

        try (Workbook in = WorkbookFactory.create(SOURCE.toFile())) {
            Sheet sheet = in.getSheetAt(0);
            int headerRowNum = findHeaderRow(sheet);
            assertThat(headerRowNum).as("a row carrying CLIENT NAME").isGreaterThanOrEqualTo(0);

            Map<String, Integer> theirColumns = new HashMap<>();
            Row header = sheet.getRow(headerRowNum);
            for (Cell cell : header) {
                String name = text(cell).strip().toLowerCase(Locale.ROOT);
                if (!name.isBlank()) theirColumns.put(name, cell.getColumnIndex());
            }

            for (int r = headerRowNum + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> out = new HashMap<>();
                THEIRS_TO_OURS.forEach((theirs, ours) -> {
                    Integer index = theirColumns.get(theirs);
                    if (index == null) return;
                    Cell cell = row.getCell(index);
                    out.put(ours, switch (ours) {
                        case "borrower_date_of_birth", "disbursement_date" -> isoDate(cell);
                        case "loan_principal_amount", "lender_premium_amount" -> money(cell);
                        case "loan_term_months" -> wholeNumber(cell);
                        default -> text(cell).strip();
                    });
                });
                // A row with no name and no amount is the formula residue every one of these
                // sheets carries below its data. Dropping it here keeps the rejection report
                // about real problems.
                if (out.getOrDefault("borrower_full_name", "").isBlank()
                        && out.getOrDefault("loan_principal_amount", "").isBlank()) {
                    continue;
                }
                rows.add(out);
            }
        }

        assertThat(rows).as("borrower rows read from Bumaco's sheet").isNotEmpty();

        try (Workbook out = new XSSFWorkbook()) {
            Sheet sheet = out.createSheet("Borrowers");
            Row header = sheet.createRow(0);
            for (int c = 0; c < TEMPLATE_COLUMNS.size(); c++) {
                header.createCell(c).setCellValue(TEMPLATE_COLUMNS.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<String, String> values = rows.get(r);
                for (int c = 0; c < TEMPLATE_COLUMNS.size(); c++) {
                    // EVERY cell a string, dates included: that is what keeps Excel from turning
                    // an ISO date back into a serial the moment somebody opens and saves this.
                    row.createCell(c, CellType.STRING)
                        .setCellValue(values.getOrDefault(TEMPLATE_COLUMNS.get(c), ""));
                }
            }
            for (int c = 0; c < TEMPLATE_COLUMNS.size(); c++) sheet.autoSizeColumn(c);
            Files.createDirectories(OUT.getParent());
            try (FileOutputStream file = new FileOutputStream(OUT.toFile())) {
                out.write(file);
            }
        }

        System.out.println("\nWrote " + OUT.toAbsolutePath());
        System.out.println(rows.size() + " borrower rows");
        System.out.println("\nFirst three rows as the platform will read them:");
        rows.stream().limit(3).forEach(r -> System.out.printf("  %-28s dob=%-12s disbursed=%-12s amount=%-14s term=%s%n",
            r.getOrDefault("borrower_full_name", ""), r.getOrDefault("borrower_date_of_birth", ""),
            r.getOrDefault("disbursement_date", ""), r.getOrDefault("loan_principal_amount", ""),
            r.getOrDefault("loan_term_months", "")));

        // The five the parser requires must be present on every row, or the upload bounces.
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("borrower_full_name")).isNotBlank();
            assertThat(r.get("borrower_date_of_birth")).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(r.get("disbursement_date")).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(r.get("loan_principal_amount")).isNotBlank();
            assertThat(r.get("loan_term_months")).isNotBlank();
        });
    }

    private static int findHeaderRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 40); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell cell : row) {
                if ("client name".equals(text(cell).strip().toLowerCase(Locale.ROOT))) return r;
            }
        }
        return -1;
    }

    private static String text(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : new BigDecimal(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    /** The real date behind the cell, as ISO text. A serial never reaches the output. */
    private static String isoDate(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            // Date-formatted or not: in these sheets the column IS a date, and a bare serial is
            // still a date to Excel. DateUtil converts either.
            return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        String raw = text(cell).strip();
        if (raw.isBlank()) return "";
        try {
            return LocalDate.parse(raw).format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            // Left as-is so the rejection report names the row rather than this converter
            // silently inventing a date.
            return raw;
        }
    }

    private static String money(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        String raw = text(cell).strip().replace(",", "");
        if (raw.isBlank()) return "";
        try {
            return new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static String wholeNumber(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf((long) cell.getNumericCellValue());
        }
        return text(cell).strip();
    }
}
