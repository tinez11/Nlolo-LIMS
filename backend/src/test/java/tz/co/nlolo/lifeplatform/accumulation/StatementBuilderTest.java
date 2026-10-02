package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;
import tz.co.nlolo.lifeplatform.accumulation.application.StatementBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementBuilderTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 31);

    private final List<LedgerEntryView> entries = new ArrayList<>();
    private BigDecimal running = BigDecimal.ZERO;

    private void entry(EntryType type, String amount, LocalDate on) {
        running = running.add(new BigDecimal(amount));
        entries.add(new LedgerEntryView(UUID.randomUUID(), UUID.randomUUID(), entries.size() + 1, type,
            new BigDecimal(amount), running, on, Instant.now(), "test", "test:" + entries.size(), null, null, "t", null));
    }

    @Test
    void openingIsEverythingBeforeThePeriodAndClosingAddsTheGroups() {
        entry(EntryType.CONTRIBUTION, "100000.00", LocalDate.of(2024, 12, 1));
        entry(EntryType.INTEREST, "250.00", LocalDate.of(2024, 12, 31));
        entry(EntryType.CONTRIBUTION, "50000.00", LocalDate.of(2025, 3, 1));
        entry(EntryType.ALLOCATION_CHARGE, "-500.00", LocalDate.of(2025, 3, 1));
        entry(EntryType.INTEREST, "400.00", LocalDate.of(2025, 3, 31));
        entry(EntryType.POLICY_FEE, "-1000.00", LocalDate.of(2025, 3, 31));

        StatementView s = StatementBuilder.build("POL-X", "TZS", entries, FROM, TO, entries.size());

        assertThat(s.openingBalance()).isEqualByComparingTo("100250.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("149150.00");
        // Money in, then the charges together, then interest: the builder's reading order.
        assertThat(s.groups()).extracting(StatementView.Group::type)
            .containsExactly(EntryType.CONTRIBUTION, EntryType.ALLOCATION_CHARGE, EntryType.POLICY_FEE, EntryType.INTEREST);
        assertThat(s.groups()).filteredOn(g -> g.type() == EntryType.INTEREST).singleElement()
            .satisfies(g -> assertThat(g.total()).isEqualByComparingTo("400.00"));
    }

    @Test
    void entriesPastTheLastSeqAreLeftForTheNextStatement() {
        entry(EntryType.CONTRIBUTION, "100000.00", LocalDate.of(2025, 2, 1));
        entry(EntryType.CONTRIBUTION, "50000.00", LocalDate.of(2025, 3, 1));
        StatementView s = StatementBuilder.build("POL-X", "TZS", entries, FROM, TO, 1);
        assertThat(s.closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(s.lastSeq()).isEqualTo(1);
    }

    @Test
    void groupsFollowTheOrderMoneyMoves() {
        // In, then charges, then interest, then out: the order a customer reads a bank statement in,
        // not the enum's declaration order.
        entry(EntryType.WITHDRAWAL, "-1.00", LocalDate.of(2025, 5, 1));
        entry(EntryType.INTEREST, "1.00", LocalDate.of(2025, 4, 30));
        entry(EntryType.TOP_UP, "1.00", LocalDate.of(2025, 4, 1));
        StatementView s = StatementBuilder.build("POL-X", "TZS", entries, FROM, TO, entries.size());
        assertThat(s.groups()).extracting(StatementView.Group::type)
            .containsExactly(EntryType.TOP_UP, EntryType.INTEREST, EntryType.WITHDRAWAL);
    }
}
