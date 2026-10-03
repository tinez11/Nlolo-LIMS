package tz.co.nlolo.lifeplatform.bonus.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a policy receives a declaration (product step 4, Q3 and Q7). Pure: given the status record
 * and a date, it never asks policy anything.
 *
 * <p>Deliberately NOT "in force". The user's rule: eligibility is its own rule, and whether a paid-up
 * policy still receives bonuses is the version's contract term. A suspended policy is still on the
 * books and still participates; a lapsed one keeps what it has and gets nothing new.
 */
public final class Eligibility {

    /** The civil day every valuation date is written in. A UTC day misdates 00:00-03:00 local. */
    public static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private static final Set<String> PARTICIPATING = Set.of("ACTIVE", "REINSTATED", "SUSPENDED");

    public record StatusRow(String status, BigDecimal sumAssured, Instant effectiveAt) {}

    private Eligibility() {}

    /** Why this policy gets nothing on that date -- or empty, which means it is eligible. */
    public static Optional<String> refusal(List<StatusRow> rows, LocalDate date, boolean paidUpParticipates) {
        List<StatusRow> before = asOf(rows, date);
        if (before.isEmpty()) {
            return Optional.of("The policy was not yet issued on " + date);
        }
        String status = before.get(before.size() - 1).status();
        if (PARTICIPATING.contains(status)) {
            return Optional.empty();
        }
        if ("PAID_UP".equals(status)) {
            return paidUpParticipates ? Optional.empty()
                : Optional.of("The policy was paid-up on " + date + ", and this version's paid-up policies receive no new bonuses");
        }
        return Optional.of("The policy was " + status + " on " + date);
    }

    /** The sum assured in force on that date: the latest stated at or before the end of it. */
    public static BigDecimal sumAssuredOn(List<StatusRow> rows, LocalDate date) {
        return asOf(rows, date).stream().map(StatusRow::sumAssured).filter(Objects::nonNull)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new IllegalStateException("No sum assured is recorded on or before " + date));
    }

    /** Every row effective before the start of the NEXT civil day, oldest first. */
    private static List<StatusRow> asOf(List<StatusRow> rows, LocalDate date) {
        Instant cutoff = date.plusDays(1).atStartOfDay(CIVIL_ZONE).toInstant();
        return rows.stream().filter(r -> r.effectiveAt().isBefore(cutoff))
            .sorted(Comparator.comparing(StatusRow::effectiveAt)).toList();
    }
}
