package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GuideTemplates;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ManualJournalLineFile;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ManualJournalRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IFRS 17 I4's pure rules: what a manual journal must satisfy, its lines read from a file, and the guide's template
 * library.
 */
class ManualJournalRulesTest {

    private static final Map<String, ManualJournalRules.Account> CHART = ChartOfAccountBlueprint.accounts().stream()
        .collect(Collectors.toMap(ChartOfAccountBlueprint.Seed::code, s -> new ManualJournalRules.Account(s.code(), s.name(),
            s.mode(), s.postingAllowed(), true, "TZS")));
    private static final Set<String> CODES = Set.of("CORRECTION", "ACCRUAL", "OTHER");

    private static ManualJournalInput.Line line(String account, PostingDirection side, String amount) {
        return new ManualJournalInput.Line(account, side, new BigDecimal(amount), null, null, null, null, null);
    }

    private static ManualJournalInput journal(String reasonCode, ManualJournalInput.Line... lines) {
        return new ManualJournalInput("2026-10", "TZS", "Shares issued", "Board resolution 4/2026", reasonCode, "M-01",
            null, List.of(lines));
    }

    private static String code(PostingMode mode) {
        return ChartOfAccountBlueprint.accounts().stream().filter(s -> s.postingAllowed() && s.mode() == mode)
            .findFirst().orElseThrow().code();
    }

    @Test
    void aBalancedJournalOnManualAccountsWithAReasonAndADocumentPasses() {
        String man1 = code(PostingMode.MAN);
        String man2 = ChartOfAccountBlueprint.accounts().stream()
            .filter(s -> s.postingAllowed() && s.mode() == PostingMode.MAN && !s.code().equals(man1)).findFirst()
            .orElseThrow().code();
        assertThat(ManualJournalRules.problems(journal(null, line(man1, PostingDirection.DR, "1500.00"),
                line(man2, PostingDirection.CR, "1500.00")), "2026-10", CHART, CODES, PeriodStatus.OPEN, 1))
            .isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        ManualJournalInput bad = new ManualJournalInput("2026-10", "TZS", " ", " ", null, null, LocalDate.of(2026, 10, 15),
            List.of(line(code(PostingMode.AUTO), PostingDirection.DR, "100.00"),
                line(code(PostingMode.BOTH), PostingDirection.CR, "90.00"), line("0000", PostingDirection.CR, "10.001")));
        List<String> problems = ManualJournalRules.problems(bad, "2026-10", CHART, CODES, PeriodStatus.LOCKED, 0);
        assertThat(problems).anyMatch(p -> p.contains("title"))
            .anyMatch(p -> p.contains("reason"))
            .anyMatch(p -> p.contains("Attach the document"))
            .anyMatch(p -> p.contains("is locked"))
            .anyMatch(p -> p.contains("An auto-reversal is dated the first day of a period after 2026-10"))
            .anyMatch(p -> p.contains("posted only by the system (AUTO)"))
            .anyMatch(p -> p.contains("no such account"))
            .anyMatch(p -> p.contains("BOTH account") && p.contains("reason code"))
            .anyMatch(p -> p.contains("differ by"));
    }

    /** An accrual reverses at the start of a period, never in the middle of one (spec §8: "day 1 of the next period"). */
    @Test
    void anAutoReversalIsTheFirstDayOfALaterPeriod() {
        String man1 = code(PostingMode.MAN);
        String man2 = ChartOfAccountBlueprint.accounts().stream()
            .filter(s -> s.postingAllowed() && s.mode() == PostingMode.MAN && !s.code().equals(man1)).findFirst()
            .orElseThrow().code();
        java.util.function.Function<LocalDate, List<String>> on = date -> ManualJournalRules.problems(
            new ManualJournalInput("2026-10", "TZS", "Accrued audit fee", "Engagement letter", null, null, date,
                List.of(line(man1, PostingDirection.DR, "5.00"), line(man2, PostingDirection.CR, "5.00"))),
            "2026-10", CHART, CODES, PeriodStatus.OPEN, 1);
        assertThat(on.apply(LocalDate.of(2026, 11, 1))).isEmpty();
        assertThat(on.apply(LocalDate.of(2027, 1, 1))).isEmpty();
        assertThat(on.apply(LocalDate.of(2026, 11, 15)))
            .containsExactly("An auto-reversal is dated the first day of a period after 2026-10, such as 2026-11-01");
        assertThat(on.apply(LocalDate.of(2026, 10, 1)))
            .containsExactly("An auto-reversal is dated the first day of a period after 2026-10, such as 2026-11-01");
    }

    @Test
    void aReasonCodeMustBeOneOfTheList() {
        String both = code(PostingMode.BOTH);
        String man = code(PostingMode.MAN);
        assertThat(ManualJournalRules.problems(journal("WHIM", line(both, PostingDirection.DR, "5.00"),
                line(man, PostingDirection.CR, "5.00")), "2026-10", CHART, CODES, PeriodStatus.CLOSING, 1))
            .containsExactly("Reason code WHIM is not one of " + CODES);
    }

    @Test
    void linesAreReadFromAFileAndEveryBadRowIsNamed() {
        ManualJournalLineFile.Result ok = ManualJournalLineFile.parse(
            "Side,Account,Amount,Description,Branch\nDR,1110,\"1,500,000.00\",Shares,DSM\ncredit,3110,1500000,,\n");
        assertThat(ok.errors()).isEmpty();
        assertThat(ok.lines()).hasSize(2);
        assertThat(ok.lines().get(0).amount()).isEqualByComparingTo("1500000.00");
        assertThat(ok.lines().get(1).side()).isEqualTo(PostingDirection.CR);
        assertThat(ok.lines().get(0).branch()).isEqualTo("DSM");

        ManualJournalLineFile.Result bad = ManualJournalLineFile.parse("account,side,amount\n11,DR,5\n3110,XX,-1\n");
        assertThat(bad.lines()).isEmpty();
        assertThat(bad.errors()).containsExactly(
            "Row 2: account must be a four-digit code, got '11'",
            "Row 3: side must be DR or CR, got 'XX'",
            "Row 3: amount must be a positive number with at most two decimals, got '-1'");
        assertThat(ManualJournalLineFile.parse("account,amount\n1110,5\n").errors())
            .containsExactly("The file has no 'side' column (columns: account, side, amount, description, branch, fund, reference)");
    }

    @Test
    void theGuideLibraryLoadsAndOffersOnlyEntriesAManualJournalCanPost() {
        List<GuideTemplates.Template> templates = GuideTemplates.load(
            ManualJournalRulesTest.class.getResourceAsStream("/finaccounting/manual-journal-templates.yaml"),
            ChartOfAccountBlueprint.accounts());
        assertThat(templates).hasSize(52);
        assertThat(templates.stream().filter(t -> t.postedBy() == null)).hasSize(39);
        assertThat(templates.stream().filter(t -> t.postedBy() != null).map(GuideTemplates.Template::id))
            .containsExactlyInAnyOrder("M-07", "R-01", "R-03", "R-04", "Q-01", "Q-02", "Q-03", "Q-04", "Q-06", "Q-07",
                "Q-08", "Q-09", "O-10");
        // I3c's decision Q6: the quarterly/annual statement (R-01 offset, R-03 profit commission, R-04 funds withheld)
        // is its own build after I4 -- the monthly bordereau posts none of them.
        assertThat(templates).filteredOn(t -> t.id().startsWith("R-0") && t.postedBy() != null)
            .extracting(GuideTemplates.Template::postedBy)
            .containsOnly("The reinsurance statement (built after IFRS 17 I4), from the reinsurer's quarterly or annual statement.");
        assertThat(templates).filteredOn(t -> t.id().equals("M-01")).singleElement()
            .satisfies(t -> assertThat(t.lines()).extracting(GuideTemplates.Line::account)
                .containsExactly("1110", "3110", "3120"));
    }
}
