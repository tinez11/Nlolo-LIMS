package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code returnType} is a plain, non-enum string on purpose: it is validated against seeded
 * {@code regreporting.return_definition} rows, not a fixed Java enum (see
 * {@code ReturnDefinition}'s and {@code MetricName}'s javadoc for why the return catalog is data,
 * not code) -- {@code RegreportingApi.generateReturn} throws {@code RegreportingValidationException}
 * (422) for an unknown value, rather than this DTO enumerating a closed set that would need a
 * redeploy every time a definition is added.
 *
 * <p>{@code period}'s format (e.g. {@code YYYY-Qn} vs {@code YYYY}) is likewise not a bean-
 * validation pattern here: it depends on the return type's own {@code periodKind}, which only the
 * service layer knows, and duplicating that check here would give the same malformed request two
 * different status codes depending on which layer noticed first -- the same reasoning
 * {@code CreateTreatyRequestDto}'s javadoc records for {@code cessionPercent}.
 */
public record GenerateReturnRequestDto(@NotBlank String returnType, @NotBlank String period) {}
