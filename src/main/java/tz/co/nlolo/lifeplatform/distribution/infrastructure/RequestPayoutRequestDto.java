package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code payeeRef} is the agent's mobile-money MSISDN and cannot be derived: {@code party.PartyView}
 * exposes no MSISDN, which is the same reason claims' settlement decision takes one explicitly.
 * That is also why the monthly close sweep does not trigger payouts -- closing is a state
 * transition a machine can make, paying needs this value from a human.
 */
public record RequestPayoutRequestDto(@NotBlank String payeeRef) {}
