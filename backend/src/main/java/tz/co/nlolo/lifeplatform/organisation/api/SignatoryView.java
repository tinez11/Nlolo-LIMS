package tz.co.nlolo.lifeplatform.organisation.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A person who signs the company's documents, from a day (2026-11-09).
 *
 * @param signs the {@link DocumentKind} names this person signs
 */
public record SignatoryView(UUID signatoryId, String name, String title, LocalDate effectiveFrom, LocalDate retiredOn,
                            List<String> signs, boolean hasSignature) {}
