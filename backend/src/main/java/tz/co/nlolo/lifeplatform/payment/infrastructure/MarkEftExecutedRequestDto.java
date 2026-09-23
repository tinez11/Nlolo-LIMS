package tz.co.nlolo.lifeplatform.payment.infrastructure;

import jakarta.validation.constraints.NotBlank;

/**
 * What finance types in after moving the money. {@code bankReference} is the bank's own reference
 * for the transfer — the thing a reconciliation later matches against a statement line, and the
 * only evidence the platform will ever hold that this payout actually left. It is mandatory for
 * exactly that reason: an EFT recorded as executed with nothing to look it up by is a claim marked
 * paid on somebody's word.
 */
public record MarkEftExecutedRequestDto(@NotBlank String bankReference) {}
