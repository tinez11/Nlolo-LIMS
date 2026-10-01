package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;

import java.util.UUID;

/**
 * Fresh proof that the life assured is alive.
 *
 * <p>The method is required: "proof of life was recorded" with no statement of HOW is the entry
 * that makes the control worthless, and it is the entry a hurried clerk would make.
 */
public record ProofOfLifeRequest(@NotNull ProofOfLifeMethod proofOfLifeMethod, UUID proofOfLifeDocumentId) {}
