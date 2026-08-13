package tz.co.nlolo.lifeplatform.claims.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code impairmentPercent} is serialized as a JSON <b>string</b>, not a number (M6 final-review fix
 * I4). {@code api/openapi/openapi-claims.yaml} declares it {@code type: string} with pattern
 * {@code ^\d+(\.\d{1,2})?$} -- the same "never put a decimal on the wire as a binary float"
 * convention {@code openapi-common.yaml}'s {@code Money} schema establishes, and which this field's
 * own spec description invokes by name. Without {@link JsonFormat} Jackson emitted {@code 50.00} as
 * a bare number, so REQUESTS worked (Jackson coerces String -> BigDecimal inbound) while every
 * RESPONSE carrying {@code DisabilityClaimDetails} silently violated the spec and would fail
 * {@code openApi().isValid(...)}. Nothing caught it: the only DISABILITY payload in the contract
 * tests was a deliberately-invalid 422 request, which omits the validator matcher. Now guarded by
 * {@code ClaimsContractTest.registerAndGetADisabilityClaimBothValidateAgainstTheSpec}.
 *
 * <p>{@code Claim.details} is persisted as JSONB through the same {@code ObjectMapper}, so the
 * stored JSON carries the string form too and round-trips back to {@link BigDecimal} on read.
 */
public record DisabilityClaimDetails(String disabilityType, LocalDate onsetDate, boolean permanent,
                                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal impairmentPercent)
        implements ClaimDetails {
    @Override public ClaimType claimType() { return ClaimType.DISABILITY; }
}
