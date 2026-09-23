package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;
import tz.co.nlolo.lifeplatform.policy.api.ExitRejection;
import tz.co.nlolo.lifeplatform.policy.domain.ExitCsvParser;

import java.io.StringReader;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The other half of {@code EnrolmentCsvParserTest}: the file that takes loans OFF cover.
 *
 * <p>Tested as a pure function over strings for the same reason — every value arrives from a
 * counterparty's spreadsheet export, and the awkward cases are not invented. They are the same
 * ones the real LOLC and BUMACO schedules contain, because the exits file comes out of the same
 * spreadsheets.
 *
 * <p>One difference in consequence is worth holding onto while reading. A bad row on an
 * enrolment file leaves somebody UNINSURED, which is loud: the borrower or the lender
 * eventually notices. A bad row here leaves somebody still insured and still being charged,
 * which nobody notices at all — so the report matters just as much and for the opposite reason.
 */
class ExitCsvParserTest {

    private static final String HEADER =
        "member_reference,exit_date,exit_reason,outstanding_balance_at_exit\n";

    private static ExitCsvParser.ParsedExits parse(String body) {
        return ExitCsvParser.parse(new StringReader(HEADER + body));
    }

    // ---- the ordinary case --------------------------------------------------

    @Test
    void aCompleteRowParsesIntoTypedValues() {
        var parsed = parse("CL-4F8DF58B-000417,2026-09-15,SETTLED_EARLY,0.00\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        var row = parsed.rows().get(0);
        assertThat(row.memberReference()).isEqualTo("CL-4F8DF58B-000417");
        assertThat(row.exitDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(row.exitReason()).isEqualTo(ExitReason.SETTLED_EARLY);
        assertThat(row.outstandingBalanceAtExit()).isEqualByComparingTo("0.00");
        assertThat(row.lineNumber()).isEqualTo(2);
    }

    @Test
    void theBalanceMayBeBlankBecauseNotEveryLenderTracksIt() {
        // It is recorded, never trusted -- our own declining schedule values a claim. A lender
        // who cannot produce a figure must still be able to tell us the loan ended.
        var parsed = parse("CL-A-000001,2026-09-15,SETTLED_EARLY,\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).outstandingBalanceAtExit()).isNull();
    }

    @Test
    void everyReasonTheLenderMayGiveIsAccepted() {
        var parsed = parse("CL-A-000001,2026-09-15,SETTLED_EARLY,0.00\n"
            + "CL-A-000002,2026-09-16,REFINANCED,4180000.00\n"
            + "CL-A-000003,2026-09-17,WRITTEN_OFF,2210000.00\n"
            + "CL-A-000004,2026-09-18,CANCELLED,12750000.00\n");

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).extracting(r -> r.exitReason())
            .containsExactly(ExitReason.SETTLED_EARLY, ExitReason.REFINANCED,
                             ExitReason.WRITTEN_OFF, ExitReason.CANCELLED);
    }

    // ---- a bad row is a bad row, not a bad file ------------------------------

    @Test
    void aMissingRequiredFieldIsARowErrorAndNotAFileError() {
        // One bad row must not strand 399 refunds.
        var parsed = parse("CL-A-000001,2026-09-15,SETTLED_EARLY,0.00\n"
            + ",2026-09-16,SETTLED_EARLY,0.00\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors()).hasSize(1);
        assertThat(parsed.errors().get(0).reason())
            .isEqualTo(ExitRejection.MISSING_REQUIRED_FIELD);
        assertThat(parsed.errors().get(0).detail()).contains("member_reference");
        assertThat(parsed.errors().get(0).lineNumber()).isEqualTo(3);
    }

    @Test
    void anUnknownExitReasonIsRefusedRatherThanStoredAsFreeText() {
        // chk_exit_row_reason_known would refuse it at acceptance, half-way through a file,
        // after earlier loans had already been taken off cover. And a reason nothing
        // downstream recognises would silently never refund and never claw back.
        var parsed = parse("CL-A-000001,2026-09-15,PAID OFF,0.00\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(ExitRejection.UNKNOWN_EXIT_REASON);
        assertThat(parsed.errors().get(0).detail()).contains("PAID OFF")
            .contains("SETTLED_EARLY");
    }

    @Test
    void aLenderMayNotClaimTheInsurerPaidAClaim() {
        // CLAIM_SETTLED is a real ExitReason, so this would parse if the check were only
        // "is it in the enum". It is refused because only dischargeForSettledClaim may write
        // it: a lender asserting it here would suppress the refund AND the clawback on a loan
        // that was merely repaid -- the insurer keeps premium it did not earn and the bank
        // keeps commission on it.
        var parsed = parse("CL-A-000001,2026-09-15,CLAIM_SETTLED,0.00\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(ExitRejection.UNKNOWN_EXIT_REASON);
        assertThat(parsed.errors().get(0).detail()).contains("claim");
    }

    @Test
    void anExcelSerialDateIsRefusedRatherThanRead() {
        // 46203 is 2026-06-30 to Excel and nothing at all to LocalDate. Reading it as a year
        // would date a refund to the wrong century.
        var parsed = parse("CL-A-000001,46203,SETTLED_EARLY,0.00\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(ExitRejection.MALFORMED_VALUE);
        assertThat(parsed.errors().get(0).detail()).contains("exit_date").contains("46203");
    }

    @Test
    void anUnparseableBalanceNamesTheColumnAndTheValue() {
        // Excel exports a large number as 4.18E+06. BigDecimal would ACCEPT that silently.
        var parsed = parse("CL-A-000001,2026-09-15,SETTLED_EARLY,4.18E+06\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors().get(0).reason()).isEqualTo(ExitRejection.MALFORMED_VALUE);
        assertThat(parsed.errors().get(0).detail())
            .contains("outstanding_balance_at_exit").contains("4.18E+06");
    }

    @Test
    void aNegativeBalanceIsRefused() {
        // chk_exit_row... would refuse it anyway; here it arrives as a sentence.
        var parsed = parse("CL-A-000001,2026-09-15,SETTLED_EARLY,-100.00\n");

        assertThat(parsed.errors().get(0).reason()).isEqualTo(ExitRejection.MALFORMED_VALUE);
    }

    @Test
    void theSameReferenceTwiceInOneFileIsCaughtHere() {
        // A lender who pasted a block twice hears about it against their own line numbers,
        // before acceptance touches anybody.
        var parsed = parse("CL-A-000001,2026-09-15,SETTLED_EARLY,0.00\n"
            + "CL-A-000001,2026-09-20,REFINANCED,500.00\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors().get(0).reason()).isEqualTo(ExitRejection.DUPLICATE_REFERENCE);
        assertThat(parsed.errors().get(0).detail()).contains("line 2");
    }

    @Test
    void onlyTheFirstProblemOnARowIsReported() {
        var parsed = parse(",not-a-date,NONSENSE,not-an-amount\n");

        assertThat(parsed.errors()).hasSize(1);
    }

    // ---- what the real exports look like ------------------------------------

    @Test
    void aByteOrderMarkDoesNotHideTheFirstColumn() {
        // Excel writes UTF-8 with a BOM, which otherwise makes member_reference read as
        // absent -- sending the lender to look for a column that is right there.
        var parsed = ExitCsvParser.parse(new StringReader(
            "﻿" + HEADER + "CL-A-000001,2026-09-15,SETTLED_EARLY,0.00\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).memberReference()).isEqualTo("CL-A-000001");
    }

    @Test
    void headersAreMatchedCaseAndSpaceInsensitivelyAndSoAreReasons() {
        var parsed = ExitCsvParser.parse(new StringReader(
            " Member_Reference ,EXIT_DATE,exit_reason,Outstanding_Balance_At_Exit\n"
            + "CL-A-000001,2026-09-15,settled_early,0.00\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).exitReason()).isEqualTo(ExitReason.SETTLED_EARLY);
    }

    @Test
    void anExtraColumnTheTemplateDoesNotKnowIsIgnored() {
        // Both real lender files carry columns we do not use. Refusing a file for carrying
        // more than we asked for would reject every real export on day one.
        var parsed = ExitCsvParser.parse(new StringReader(
            "s_no,member_reference,borrower_name,exit_date,exit_reason,"
            + "outstanding_balance_at_exit,branch\n"
            + "1,CL-A-000001,Amina Hassan Mwinyi,2026-09-15,SETTLED_EARLY,0.00,Kariakoo\n"));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).memberReference()).isEqualTo("CL-A-000001");
    }

    @Test
    void trailingBlankRowsAreSkippedRatherThanRejected() {
        // The real sheets carry hundreds of rows of formula residue below the data.
        var parsed = parse("CL-A-000001,2026-09-15,SETTLED_EARLY,0.00\n,,,\n,,,\n");

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.errors()).isEmpty();
    }

    // ---- whole-file problems ------------------------------------------------

    @Test
    void aMissingRequiredColumnFailsTheWholeFile() {
        // A header problem is not a row problem: every row would fail identically, and 400
        // identical rejections tell the lender less than one sentence does.
        assertThatThrownBy(() -> ExitCsvParser.parse(new StringReader(
            "member_reference,exit_date\nCL-A-000001,2026-09-15\n")))
            .isInstanceOf(ExitCsvParser.MalformedExitsFileException.class)
            .hasMessageContaining("exit_reason");
    }

    @Test
    void anEmptyFileIsAFileError() {
        assertThatThrownBy(() -> ExitCsvParser.parse(new StringReader("")))
            .isInstanceOf(ExitCsvParser.MalformedExitsFileException.class)
            .hasMessageContaining("no header");
    }

    // ---- the template the lender is given ----------------------------------

    /** The blank exits file we hand a lender must be readable by the parser that judges it. */
    @Test
    void theTemplateWeGiveTheLenderParses() {
        String filled = ExitCsvParser.templateCsv()
            + "CL-4F8DF58B-000417,2026-09-01,SETTLED_EARLY,450000.00\n";

        var parsed = ExitCsvParser.parse(new StringReader(filled));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).memberReference()).isEqualTo("CL-4F8DF58B-000417");
        assertThat(parsed.rows().get(0).exitReason()).isEqualTo(ExitReason.SETTLED_EARLY);
    }

    @Test
    void theTemplateCarriesEveryColumnTheParserRequires() {
        String header = ExitCsvParser.templateCsv().strip();
        assertThat(ExitCsvParser.requiredColumns())
            .allSatisfy(column -> assertThat(header).contains(column));
    }

    /**
     * CLAIM_SETTLED is not offered to a lender, although it is a valid ExitReason.
     *
     * <p>A lender stating it would suppress both the refund and the commission clawback on a loan
     * that was merely repaid, so only the claim path may write it.
     */
    @Test
    void aLenderIsNotOfferedTheClaimReason() {
        assertThat(ExitCsvParser.lenderStateableReasons()).doesNotContain(ExitReason.CLAIM_SETTLED);
    }
}
