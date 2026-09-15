package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;

/**
 * Mirrors {@code openapi-product.yaml}'s {@code TiraFiling}. Required on every publish.
 *
 * <p>{@code @PastOrPresent} mirrors the record's own refusal of a future approval date, so the
 * caller gets a 400 naming the field rather than a 422 from deeper in the service.
 */
public record TiraFilingRequest(
    @NotBlank String reference,
    @NotNull @PastOrPresent LocalDate approvalDate) {}
