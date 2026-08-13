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
 * RESPONSE carrying {@code DisabilityClaimDetails} silently violated the spec.
 *
 * <p><b>What actually guards this, corrected after the fix wave's own re-review empirically
 * disproved the first version of this comment:</b> {@code openApi().isValid(...)} does NOT catch it.
 * The re-review reverted this annotation and observed the matcher PASS on a response body carrying
 * a bare {@code 62.50} number against this very {@code type: string} declaration -- so
 * swagger-request-validator does not enforce string-vs-number for body properties, and claiming it
 * would have left a false sense of coverage here (and, more broadly, across the nine contract-test
 * classes that lean on that matcher). The real regression guard is the type-strict
 * {@code jsonPath("$.details.impairmentPercent").value("62.50")} assertion in
 * {@code ClaimsContractTest.registerAndGetADisabilityClaimBothValidateAgainstTheSpec}, which fails
 * on {@code 62.5} vs {@code "62.50"}. Pair a strict jsonPath with {@code isValid} for any
 * string-typed decimal; do not rely on {@code isValid} alone.
 *
 * <p>{@code Claim.details} is persisted as JSONB through the same {@code ObjectMapper}, so the
 * stored JSON carries the string form too and round-trips back to {@link BigDecimal} on read.
 */
public record DisabilityClaimDetails(String disabilityType, LocalDate onsetDate, boolean permanent,
                                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal impairmentPercent)
        implements ClaimDetails {
    @Override public ClaimType claimType() { return ClaimType.DISABILITY; }
}
