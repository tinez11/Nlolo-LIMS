package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;

import java.io.StringReader;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every value here arrives from a counterparty's spreadsheet export, so the parser is
 * tested as a pure function over strings with no database and no Spring -- the same
 * reasoning as AmortisationCalculatorTest.
 *
 * <p>The awkward cases are not invented. Each one is something the real LOLC and BUMACO
 * schedules actually contain.
 */
class EnrolmentCsvParserTest {

    private static final String HEADER =
        "loan_account_number,borrower_full_name,borrower_date_of_birth,borrower_sex,"
        + "borrower_national_id,borrower_phone,loan_principal_amount,"
        + "loan_term_months,disbursement_date\n";

    private static EnrolmentCsvParser.ParsedSchedule parse(String body) {
        return EnrolmentCsvParser.parse(new StringReader(HEADER + body));
    }

    // ---- the ordinary case --------------------------------------------------

    @Test
    void aCompleteRowParsesIntoTypedValues() {
        var parsed = parse("LN-2026-00417,Amina Hassan Mwinyi,1988-03-14,F,,,"
            + "8500000.00,48,2026-08-03\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        var row = parsed.rows().get(0);
        assertThat(row.loanAccountNumber()).isEqualTo("LN-2026-00417");
        assertThat(row.borrowerFullName()).isEqualTo("Amina Hassan Mwinyi");
        assertThat(row.borrowerDateOfBirth()).isEqualTo(LocalDate.of(1988, 3, 14));
        assertThat(row.loanPrincipalAmount()).isEqualByComparingTo("8500000.00");
        assertThat(row.loanTermMonths()).isEqualTo(48);
        assertThat(row.disbursementDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(row.lineNumber()).isEqualTo(2);
    }

    @Test
    void theThreeIdentityColumnsMayBeBlank() {
        // Sex, national ID and phone are optional and always were. BUMACO sends gender;
        // neither lender sends a national ID, which is why party de-duplication cannot
        // fire on a borrower and the loan account number keys the member instead.
        var parsed = parse("LN-2026-00418,Joseph Mkenda,1975-11-02,,,,24000000.00,60,2026-08-05\n");

        assertThat(parsed.errors()).isEmpty();
        var row = parsed.rows().get(0);
        assertThat(row.borrowerSex()).isNull();
        assertThat(row.borrowerNationalId()).isNull();
        assertThat(row.borrowerPhone()).isNull();
        assertThat(row.loanPrincipalAmount()).isEqualByComparingTo("24000000.00");
    }

    // ---- a bad row is a bad row, not a bad file ------------------------------

    @Test
    void aMissingRequiredFieldIsARowErrorAndNotAFileError() {
        // One bad row must not cost 399 people their cover.
        var parsed = parse("LN-A,Good Borrower,1990-01-01,F,,,1000000.00,12,2026-08-01\n"
            + "LN-B,,1991-05-23,F,,,5000000.00,36,2026-08-17\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors()).hasSize(1);
        assertThat(parsed.errors().get(0).reason())
            .isEqualTo(EnrolmentRejection.MISSING_REQUIRED_FIELD);
        assertThat(parsed.errors().get(0).detail()).contains("borrower_full_name");
        assertThat(parsed.errors().get(0).lineNumber()).isEqualTo(3);
    }

    @Test
    void anUnparseableAmountNamesTheColumnAndTheValue() {
        // Excel exports a large number as 8.5E+06. BigDecimal would ACCEPT that string
        // silently, so it is refused explicitly rather than read.
        var parsed = parse("LN-C,Bad Amount,1990-01-01,F,,,8.5E+06,12,2026-08-01\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(EnrolmentRejection.MALFORMED_VALUE);
        assertThat(parsed.errors().get(0).detail())
            .contains("loan_principal_amount").contains("8.5E+06");
    }

    @Test
    void anExcelSerialDateIsRefusedRatherThanRead() {
        // 46203 is 2026-06-30 to Excel and nothing at all to LocalDate. Reading it as a
        // year would insure the wrong person for the wrong term, so it is refused by
        // name -- and the message is what tells the lender their export is wrong.
        var parsed = parse("LN-SERIAL,Amina,34237,F,,,1000000.00,12,46203\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(EnrolmentRejection.MALFORMED_VALUE);
        assertThat(parsed.errors().get(0).detail())
            .contains("borrower_date_of_birth").contains("34237");
    }

    @Test
    void aNonNumericTermIsRefused() {
        var parsed = parse("LN-TERM,Amina,1990-01-01,F,,,1000000.00,twelve,2026-08-01\n");

        assertThat(parsed.errors().get(0).reason()).isEqualTo(EnrolmentRejection.MALFORMED_VALUE);
        assertThat(parsed.errors().get(0).detail()).contains("loan_term_months").contains("twelve");
    }

    @Test
    void aDuplicateLoanWithinOneFileIsCaughtHere() {
        // The lender supplies no identifier of their own, so the LOAN is the identity:
        // who, born when, borrowed how much, on what day. Caught within the file first, so
        // a lender who pasted a block twice hears about it against their own line numbers.
        var parsed = parse("LN-DUP,Amina Hassan Mwinyi,1990-01-01,F,,,1000000.00,12,2026-08-01\n"
            + "LN-DUP,Amina Hassan Mwinyi,1990-01-01,M,,,1000000.00,12,2026-08-01\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors().get(0).reason())
            .isEqualTo(EnrolmentRejection.DUPLICATE_LOAN);
        assertThat(parsed.errors().get(0).detail()).contains("line 2");
    }

    @Test
    void onlyTheFirstProblemOnARowIsReported() {
        // A lender fixing a row fixes it whole. Five findings on one line is noise.
        var parsed = parse("LN-MULTI,,not-a-date,F,,,not-an-amount,nope,also-not-a-date\n");

        assertThat(parsed.errors()).hasSize(1);
    }

    // ---- what the real exports look like ------------------------------------

    @Test
    void aByteOrderMarkDoesNotHideTheFirstColumn() {
        // Excel writes UTF-8 with a BOM. Without stripping it the first header reads
        // "﻿loan_account_number" and the file fails as if the column were absent --
        // exactly the confusing failure this test exists to prevent.
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            "﻿" + HEADER + "LN-BOM,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-BOM");
    }

    @Test
    void headersAreMatchedCaseAndSpaceInsensitively() {
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            " Loan_Account_Number ,Borrower_Full_Name,BORROWER_DATE_OF_BIRTH,borrower_sex,"
            + "borrower_national_id,borrower_phone,loan_principal_amount,"
            + "loan_term_months,disbursement_date\n"
            + "LN-CASE,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-CASE");
    }

    @Test
    void anExtraColumnTheTemplateDoesNotKnowIsIgnored() {
        // Both real lender files carry columns we do not use -- S/N, AGE, premium per
        // policy year. Refusing a file for carrying more than we asked for would reject
        // every real export on day one.
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            "s_no,loan_account_number,borrower_full_name,borrower_date_of_birth,borrower_sex,"
            + "borrower_national_id,borrower_phone,loan_principal_amount,"
            + "loan_term_months,disbursement_date,age,total_premium\n"
            + "1,LN-EXTRA,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01,36,52000.00\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-EXTRA");
    }

    @Test
    void trailingBlankRowsAreSkippedRatherThanRejected() {
        // The real LOLC sheet carries ~200 trailing rows of formula residue below the
        // data. Rejecting them would bury 8 real rejections under 200 noise ones.
        var parsed = parse("LN-REAL,Amina,1990-01-01,F,,,1000000.00,12,2026-08-01\n"
            + ",,,,,,,,\n,,,,,,,,\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors()).isEmpty();
    }

    // ---- whole-file problems ------------------------------------------------

    @Test
    void aMissingRequiredColumnFailsTheWholeFile() {
        // A header problem is not a row problem: every row would fail identically, and
        // 400 identical rejections tell the lender less than one sentence does.
        assertThatThrownBy(() -> EnrolmentCsvParser.parse(new StringReader(
            "borrower_full_name,loan_principal_amount\nAmina,8500000.00\n")))
            .isInstanceOf(EnrolmentCsvParser.MalformedScheduleException.class)
            .hasMessageContaining("borrower_date_of_birth");
    }

    @Test
    void aFileWithNoLoanAccountNumberColumnIsFine() {
        // Required until 2026-09-22, when the client confirmed neither lender holds one.
        // Requiring it would have rejected every real file.
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            "borrower_full_name,borrower_date_of_birth,borrower_sex,borrower_national_id,"
            + "borrower_phone,loan_principal_amount,loan_term_months,disbursement_date\n"
            + "Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,48,2026-08-03\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanAccountNumber()).isNull();
        assertThat(parsed.rows().get(0).memberReference()).isNull();
    }

    @Test
    void aReferenceQuotedBackByTheLenderIsRead() {
        var parsed = EnrolmentCsvParser.parse(new StringReader(
            "member_reference,borrower_full_name,borrower_date_of_birth,borrower_sex,"
            + "borrower_national_id,borrower_phone,loan_principal_amount,loan_term_months,"
            + "disbursement_date\n"
            + "CL-4F8DF58B-000417,Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,48,2026-08-03\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).memberReference()).isEqualTo("CL-4F8DF58B-000417");
    }

    @Test
    void anEmptyFileIsAFileError() {
        assertThatThrownBy(() -> EnrolmentCsvParser.parse(new StringReader("")))
            .isInstanceOf(EnrolmentCsvParser.MalformedScheduleException.class)
            .hasMessageContaining("no header");
    }

    // ---- the template the lender is given ----------------------------------

    /**
     * The blank file we hand a lender must be readable by the parser that judges it.
     *
     * <p>This is the whole point of generating the template from the column constants. Three
     * refusal messages told people to "use the template at credit-life-enrolment-sample.csv", a
     * file that lived only in the repository, so the format reached a lender by description --
     * and the HEADER constant at the top of this very test file is a hand-written copy of the
     * same columns, which is exactly the drift this guards against.
     */
    @Test
    void theTemplateWeGiveTheLenderParses() {
        String filled = EnrolmentCsvParser.templateCsv()
            + ",Amina Hassan Mwinyi,1988-03-14,F,,,8500000.00,48,2026-08-03,LN-2026-00417\n";

        var parsed = EnrolmentCsvParser.parse(new StringReader(filled));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).borrowerFullName()).isEqualTo("Amina Hassan Mwinyi");
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-2026-00417");
    }

    /** A template missing a required column is a file the lender cannot possibly get right. */
    @Test
    void theTemplateCarriesEveryColumnTheParserRequires() {
        String header = EnrolmentCsvParser.templateCsv().strip();
        assertThat(EnrolmentCsvParser.requiredColumns())
            .allSatisfy(column -> assertThat(header).contains(column));
    }

    /**
     * No example row, deliberately.
     *
     * <p>A template carrying a plausible borrower is one somebody returns with the example still
     * in it, and that row would enrol a person who does not exist.
     */
    @Test
    void theTemplateIsAHeaderAndNothingElse() {
        assertThat(EnrolmentCsvParser.templateCsv().strip().lines()).hasSize(1);
    }

    // ---- what the refusal tells a lender to do -----------------------------

    /**
     * The advice has to match the shape that failed.
     *
     * <p>Every unparseable date used to come back "A number here is an Excel date serial; format
     * the column as a date before exporting." For 46203 that is right. For "1-Sep-00" it points
     * the wrong way: formatting the column as a DATE is what produced that value, so a lender
     * following the advice would do it again and get the same refusal. The first real file sent
     * through this platform bounced entirely on exactly that value.
     */
    @Test
    void anExcelSerialIsNamedAsOne() {
        var parsed = parse("LN-1,Amina Hassan Mwinyi,46203,F,,,8500000.00,48,2026-08-03\n");

        assertThat(parsed.errors()).hasSize(1);
        assertThat(parsed.errors().get(0).detail()).contains("Excel date serial");
    }

    @Test
    void aTwoDigitYearIsRefusedRatherThanGuessed() {
        // 00 is 1900 or 2000, a century apart, and this is a date of birth: one reading is a
        // plausible borrower and the other is refused by the entry-age gate. Nothing in the file
        // breaks the tie, so the lender does.
        var parsed = parse("LN-1,Martin Fred Lema,1-Sep-00,M,,,900000.00,12,2026-09-01\n");

        assertThat(parsed.errors()).hasSize(1);
        String detail = parsed.errors().get(0).detail();
        assertThat(detail).contains("two-digit year");
        assertThat(detail).contains("2000-09-01");
        assertThat(detail).doesNotContain("Excel date serial");
    }

    @Test
    void anyOtherUnreadableDateSaysWhatExcelDidAndWhatToTypeInstead() {
        var parsed = parse("LN-1,Amina Hassan Mwinyi,03/08/2026,F,,,8500000.00,48,2026-08-03\n");

        assertThat(parsed.errors()).hasSize(1);
        assertThat(parsed.errors().get(0).detail()).contains("Set the column format to Text");
    }
}
