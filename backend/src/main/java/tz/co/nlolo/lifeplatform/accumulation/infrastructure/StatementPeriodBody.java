package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record StatementPeriodBody(@NotNull LocalDate from, @NotNull LocalDate to) {}
