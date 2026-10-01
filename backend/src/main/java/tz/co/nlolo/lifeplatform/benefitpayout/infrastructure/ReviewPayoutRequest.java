package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;

import java.util.UUID;

/**
 * Reviewing a payout: where the money goes, and -- for a survival or income payout -- how the life
 * assured was confirmed alive. The method is optional HERE because a maturity needs none; whether
 * this particular payout does is the domain's call, not the wire's.
 */
public record ReviewPayoutRequest(@NotBlank @Size(max = 200) String payeeRef,
                                  ProofOfLifeMethod proofOfLifeMethod,
                                  UUID proofOfLifeDocumentId) {}
