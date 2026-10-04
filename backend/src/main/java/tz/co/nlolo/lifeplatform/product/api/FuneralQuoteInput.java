package tz.co.nlolo.lifeplatform.product.api;

import java.time.LocalDate;
import java.util.List;

/** A family to price on one plan: every life, the main member included, with ages taken on {@code asOf}. */
public record FuneralQuoteInput(String planCode, PremiumFrequency frequency, LocalDate asOf, List<FuneralLifeInput> lives) {

    public FuneralQuoteInput {
        lives = lives != null ? List.copyOf(lives) : List.of();
    }
}
