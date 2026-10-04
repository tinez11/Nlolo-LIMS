package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;

import java.time.LocalDate;
import java.util.List;

/** A family to quote. {@code asOf} defaults to today's civil date; every life, the main member included. */
public record FuneralQuoteRequest(String planCode, PremiumFrequency frequency, LocalDate asOf, @Valid List<Life> lives) {

    public record Life(FuneralRole role, String name, LocalDate dateOfBirth, Boolean student) {}

    FuneralQuoteInput toInput(LocalDate today) {
        return new FuneralQuoteInput(planCode, frequency, asOf != null ? asOf : today,
            lives == null ? List.of() : lives.stream()
                .map(l -> new FuneralLifeInput(l.role(), l.name(), l.dateOfBirth(), Boolean.TRUE.equals(l.student()))).toList());
    }
}
