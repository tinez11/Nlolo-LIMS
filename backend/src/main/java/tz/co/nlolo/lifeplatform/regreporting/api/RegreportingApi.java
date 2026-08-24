package tz.co.nlolo.lifeplatform.regreporting.api;

import java.util.List;
import java.util.UUID;

/**
 * The `regreporting` module's public surface.
 *
 * <p>Everything this module reports on arrives as a domain event -- {@code allowedDependencies} is
 * exactly {@code { refdata::api }} and no other module is ever called. There is deliberately no
 * method to write a projection: the read model is derived, and the only way to change it is to
 * publish the business event that caused the change.
 *
 * <p>TIRA's return catalog is C2-blocked. One placeholder definition ships (seeded by
 * {@code regreporting/V2}); every line code and label in it is invented and flagged.
 */
public interface RegreportingApi {

    /**
     * Generates (or REGENERATES) the return for {@code returnType} and {@code period}, synchronously.
     *
     * <p>Idempotent per {@code (tenant, returnType, period)}: regenerating replaces the prior lines
     * rather than accumulating duplicates, because a return is a derived artifact and re-deriving it
     * must be safe.
     *
     * @throws RegreportingValidationException if no definition exists for {@code returnType}, or if
     *         {@code period}'s format does not match that definition's {@code periodKind}
     */
    RegulatoryReturnView generateReturn(String returnType, String period, String generatedBy);

    /** @param period optional filter; null returns every return for the tenant */
    List<RegulatoryReturnView> listReturns(String period);

    /** @throws ReturnNotFoundException if no such return exists FOR THIS TENANT */
    RegulatoryReturnView getReturn(UUID returnId);
}
