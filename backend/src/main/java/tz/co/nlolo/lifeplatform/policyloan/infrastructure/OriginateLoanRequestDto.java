package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** payeeRef is @NotBlank at the wire boundary: openapi-policyloan.yaml's requestBody schema
 * lists it in required: [requestedAmount, payeeRef] and does not mark it nullable, even though
 * PolicyLoanApiImpl.originateLoan internally tolerates a null payeeRef for non-HTTP callers
 * (it builds the LoanDisbursementRequested payload with a LinkedHashMap specifically to allow
 * that). The HTTP contract is stricter than the internal API on purpose. */
public record OriginateLoanRequestDto(@NotNull @Valid MoneyDto requestedAmount, @NotBlank String payeeRef) {}
