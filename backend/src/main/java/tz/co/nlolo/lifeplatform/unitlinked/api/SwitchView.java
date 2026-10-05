package tz.co.nlolo.lifeplatform.unitlinked.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A fund switch (U2): its legs, the date both legs are priced on (the binding; the execution may be later if a fund
 * has no price that day), and the fee it paid once executed.
 */
public record SwitchView(UUID switchId, String policyNumber, List<Leg> legs, LocalDate boundDate, String status,
                         LocalDate executedOn, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fee,
                         String requestedBy, Instant requestedAt) {

    /** {@code side} OUT: {@code percent}% of the fund's units; IN: {@code percent}% of the net proceeds. */
    public record Leg(String fundCode, String side, int percent) {}
}
