package tz.co.nlolo.lifeplatform.product.api;

import java.time.LocalDate;

/** One life to price on a funeral plan. {@code student} matters only for a child. */
public record FuneralLifeInput(FuneralRole role, String name, LocalDate dateOfBirth, boolean student) {}
