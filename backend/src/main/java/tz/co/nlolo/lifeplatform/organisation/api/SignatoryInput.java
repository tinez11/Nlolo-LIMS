package tz.co.nlolo.lifeplatform.organisation.api;

import java.time.LocalDate;
import java.util.List;

/** A signatory as an administrator saves one; the signature image is uploaded on its own. */
public record SignatoryInput(String name, String title, LocalDate effectiveFrom, LocalDate retiredOn, List<String> signs) {}
