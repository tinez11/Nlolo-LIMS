package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record StatementView(String policyNumber, LocalDate periodFrom, LocalDate periodTo, int lastSeq,
                            BigDecimal openingBalance, List<Group> groups, BigDecimal closingBalance, String currency) {

    public record Group(EntryType type, BigDecimal total, List<LedgerEntryView> entries) {}

    /** INTEREST's total, the figure the annual SMS reports. Zero when none was credited. */
    public BigDecimal interestCredited() {
        return groups.stream().filter(g -> g.type() == EntryType.INTEREST).map(Group::total)
            .findFirst().orElse(BigDecimal.ZERO.setScale(2));
    }
}
