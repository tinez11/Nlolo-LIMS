package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One beneficiary named on the proposal form.
 *
 * <p>Deliberately NOT {@code policy.api.BeneficiaryInput}, which this mirrors field for field.
 * The {@code underwriting} module's allowedDependencies are party, product, document and
 * refdata — it does not depend on {@code policy}, and it must not start to, because policy
 * depends on underwriting and the cycle would be immediate. The issuance listener already
 * lives on the policy side and translates one shape into the other.
 *
 * <p>The duplication is the cost of the boundary, and it is deliberate rather than sloppy: the
 * two records exist to say the same thing at two points in time, before and after a policy
 * exists, and keeping them identical is what lets the translation be a mapping rather than an
 * interpretation.
 *
 * @param type PARTY for someone on the platform's own party register, FREEFORM for a name
 *     written on the form. Exactly one of {@code partyId} and {@code freeformDesignee} is set,
 *     enforced by {@code chk_proposal_beneficiary_exactly_one_designation}.
 * @param sharePercent this nomination's share. Shares across a proposal must total 100 if any
 *     nomination is given at all, checked in the service — the column can only bound one row.
 * @param revocable whether the policyholder may change this nomination later. An irrevocable
 *     nomination is a real instrument and cannot be revised without the beneficiary's consent.
 */
public record BeneficiaryNomination(
    NominationType type,
    UUID partyId,
    String freeformDesignee,
    BigDecimal sharePercent,
    boolean revocable) {}
